"""Black-box integration tests for Record deletion tombstones and Delivery supersession (Ticket 04)."""
from __future__ import annotations

import pathlib
import time
import uuid

import httpx

from harness import (
    GatewayProcess,
    McpAgent,
    World,
    assert_valid_contract,
    gateway,  # noqa: F401
    make_submission,
    world,  # noqa: F401
)


def test_deleting_record_creates_persistent_tombstone_and_blocks_resurrection(
    tmp_path: pathlib.Path,
) -> None:
    data_dir = tmp_path / "delete-tombstone"
    gw = GatewayProcess(data_dir)
    gw.start()
    try:
        paired = gw.pair_device("Owner Phone")
        token = paired["device_token"]
        headers = {"Authorization": f"Bearer {token}"}
        dest = gw.create_agent_destination(token, "Reader Agent")
        agent = McpAgent(gw.base_url, dest.credential)

        record_id = str(uuid.uuid4())
        stored = gw.publish(token, [dest.destination_id], record_id=record_id, revision=1, text="会被删除的口述。")
        delivery_id = stored["deliveries"][0]["delivery_id"]

        del_resp = httpx.delete(
            f"{gw.base_url}/v1/records/{record_id}",
            headers=headers,
            timeout=5.0,
        )
        assert del_resp.status_code == 200, del_resp.text
        deleted = del_resp.json()
        assert_valid_contract("revision.schema.json#/$defs/record_deleted", deleted)
        assert deleted["record_id"] == record_id
        assert deleted["cancelled_delivery_ids"] == [delivery_id]

        # Repeating DELETE is idempotent and returns the original tombstone.
        del_again = httpx.delete(
            f"{gw.base_url}/v1/records/{record_id}",
            headers=headers,
            timeout=5.0,
        )
        assert del_again.status_code == 200
        assert del_again.json() == deleted

        # Agent cannot claim the cancelled delivery.
        assert agent.claim()["deliveries"] == []

        # Re-uploading revision 1 or submitting revision 2 is rejected with RECORD_DELETED.
        for rev, text in ((1, "会被删除的口述。"), (2, "试图复活的第二版。")):
            sub = make_submission(record_id=record_id, revision=rev, text=text, destination_ids=[dest.destination_id])
            resp = httpx.put(
                f"{gw.base_url}/v1/records/{record_id}/revisions/{rev}",
                json=sub,
                headers=headers,
                timeout=5.0,
            )
            assert resp.status_code == 410, resp.text
            err = resp.json()
            assert_valid_contract("error.schema.json", err)
            assert err["code"] == "RECORD_DELETED"
            assert err["retryable"] is False

        # Deleting a record_id before any revision was uploaded also plants a tombstone.
        unsynced_id = str(uuid.uuid4())
        unsynced_del = httpx.delete(
            f"{gw.base_url}/v1/records/{unsynced_id}",
            headers=headers,
            timeout=5.0,
        )
        assert unsynced_del.status_code == 200, unsynced_del.text
        unsynced_body = unsynced_del.json()
        assert_valid_contract("revision.schema.json#/$defs/record_deleted", unsynced_body)
        assert unsynced_body["cancelled_delivery_ids"] == []

        late_sub = make_submission(record_id=unsynced_id, revision=1, text="离线删除后的延迟补传。")
        late_resp = httpx.put(
            f"{gw.base_url}/v1/records/{unsynced_id}/revisions/1",
            json=late_sub,
            headers=headers,
            timeout=5.0,
        )
        assert late_resp.status_code == 410
        assert late_resp.json()["code"] == "RECORD_DELETED"
    finally:
        gw.stop()

    # Restart Gateway: tombstone persists across restarts.
    gw2 = GatewayProcess(data_dir)
    gw2.start()
    try:
        sub_after_restart = make_submission(record_id=record_id, revision=3, text="重启后再补传。")
        resp_after = httpx.put(
            f"{gw2.base_url}/v1/records/{record_id}/revisions/3",
            json=sub_after_restart,
            headers={"Authorization": f"Bearer {token}"},
            timeout=5.0,
        )
        assert resp_after.status_code == 410
        assert_valid_contract("error.schema.json", resp_after.json())
        assert resp_after.json()["code"] == "RECORD_DELETED"
        assert McpAgent(gw2.base_url, dest.credential).claim()["deliveries"] == []
    finally:
        gw2.stop()


def test_deleting_record_cancels_open_deliveries_and_preserves_acknowledged_ones(
    world: World,
) -> None:
    acked_dest = world.destination("Acked Agent")
    leased_dest = world.destination("Leased Agent")
    pending_dest = world.destination("Pending Agent")
    rejected_dest = world.destination("Rejected Agent")
    unaffected_dest = world.destination("Unaffected Agent")

    acked_agent = world.agent(acked_dest)
    leased_agent = world.agent(leased_dest)
    pending_agent = world.agent(pending_dest)
    rejected_agent = world.agent(rejected_dest)
    unaffected_agent = world.agent(unaffected_dest)

    record_id = str(uuid.uuid4())
    pub = world.publish(
        acked_dest,
        leased_dest,
        pending_dest,
        rejected_dest,
        record_id=record_id,
        revision=1,
        text="需要区分已确认和未完成的口述。",
    )
    by_dest = {d["destination_id"]: d["delivery_id"] for d in pub["deliveries"]}

    # Unaffected record stays claimable after the target record is deleted.
    other_pub = world.publish(unaffected_dest, text="另一条不受影响的口述。")

    # acked_dest claims and acknowledges; rejected_dest claims and permanently nacks.
    (acked_claim,) = acked_agent.claim()["deliveries"]
    first_ack = acked_agent.ack(acked_claim["delivery_id"], acked_claim["lease_token"], note="已归档。")

    (rejected_claim,) = rejected_agent.claim()["deliveries"]
    rejected_agent.nack(
        rejected_claim["delivery_id"],
        rejected_claim["lease_token"],
        retryable=False,
        reason="不需要。",
    )

    # leased_dest claims and holds an open lease; pending_dest has not claimed yet.
    (leased_claim,) = leased_agent.claim()["deliveries"]

    del_resp = httpx.delete(
        f"{world.gw.base_url}/v1/records/{record_id}",
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    assert del_resp.status_code == 200, del_resp.text
    deleted = del_resp.json()
    assert_valid_contract("revision.schema.json#/$defs/record_deleted", deleted)

    # Only open deliveries (PENDING and LEASED) are cancelled; terminal ones are preserved.
    assert set(deleted["cancelled_delivery_ids"]) == {
        by_dest[leased_dest.destination_id],
        by_dest[pending_dest.destination_id],
    }

    # Pending agent cannot claim it; leased agent cannot renew, ack, or nack it.
    assert pending_agent.claim()["deliveries"] == []
    for tool, args in (
        (
            "delivery_renew_lease",
            {"delivery_id": leased_claim["delivery_id"], "lease_token": leased_claim["lease_token"]},
        ),
        (
            "delivery_ack",
            {"delivery_id": leased_claim["delivery_id"], "lease_token": leased_claim["lease_token"]},
        ),
        (
            "delivery_nack",
            {
                "delivery_id": leased_claim["delivery_id"],
                "lease_token": leased_claim["lease_token"],
                "reason": "太晚了",
                "retryable": True,
            },
        ),
    ):
        err = leased_agent.call_error(tool, args)
        assert err["code"] == "REQUEST_INVALID", tool
        assert err["retryable"] is False

    # Acknowledged delivery keeps its acknowledgement record (idempotent repeat ack succeeds with original timestamp).
    repeat_ack = acked_agent.ack(acked_claim["delivery_id"], acked_claim["lease_token"])
    assert repeat_ack["state"] == "ACKNOWLEDGED"
    assert repeat_ack["duplicate"] is True
    assert repeat_ack["acknowledged_at"] == first_ack["acknowledged_at"]

    # Rejected delivery stays terminal (not claimable, ack refused).
    assert rejected_agent.claim()["deliveries"] == []
    rej_err = rejected_agent.call_error(
        "delivery_ack",
        {"delivery_id": rejected_claim["delivery_id"], "lease_token": rejected_claim["lease_token"]},
    )
    assert rej_err["code"] == "REQUEST_INVALID"
    assert rej_err["retryable"] is False

    # Unrelated record is untouched.
    (other_claimed,) = unaffected_agent.claim()["deliveries"]
    assert other_claimed["delivery_id"] == other_pub["deliveries"][0]["delivery_id"]


def test_new_revision_cancels_unclaimed_deliveries_and_preserves_leased_or_acked(
    world: World,
) -> None:
    dest_unclaimed = world.destination("Unclaimed Agent")
    dest_leased = world.destination("Leased Agent")
    dest_acked = world.destination("Acked Agent")
    dest_new = world.destination("New Target Agent")
    dest_untouched = world.destination("Not In Rev3 Agent")

    agent_unclaimed = world.agent(dest_unclaimed)
    agent_leased = world.agent(dest_leased)
    agent_acked = world.agent(dest_acked)
    agent_new = world.agent(dest_new)
    agent_untouched = world.agent(dest_untouched)

    record_id = str(uuid.uuid4())
    pub1 = world.publish(
        dest_unclaimed,
        dest_leased,
        dest_acked,
        dest_untouched,
        record_id=record_id,
        revision=1,
        text="第一版口述。",
    )
    rev1_by_dest = {d["destination_id"]: d["delivery_id"] for d in pub1["deliveries"]}

    (leased_rev1,) = agent_leased.claim()["deliveries"]
    (acked_rev1,) = agent_acked.claim()["deliveries"]
    first_ack = agent_acked.ack(acked_rev1["delivery_id"], acked_rev1["lease_token"])

    # Publish Revision 3 (skipping 2) to dest_unclaimed, dest_leased, dest_acked, dest_new.
    pub3 = world.publish(
        dest_unclaimed,
        dest_leased,
        dest_acked,
        dest_new,
        record_id=record_id,
        revision=3,
        text="第三版修订正文。",
        source="user_edit",
    )
    assert pub3["superseded_revisions"] == [1]

    cancelled_entries = [d for d in pub3["deliveries"] if d["state"] == "CANCELLED"]
    pending_entries = [d for d in pub3["deliveries"] if d["state"] == "PENDING"]
    assert cancelled_entries == [
        {
            "delivery_id": rev1_by_dest[dest_unclaimed.destination_id],
            "destination_id": dest_unclaimed.destination_id,
            "revision": 1,
            "state": "CANCELLED",
        }
    ]
    assert {d["destination_id"] for d in pending_entries} == {
        dest_unclaimed.destination_id,
        dest_leased.destination_id,
        dest_acked.destination_id,
        dest_new.destination_id,
    }
    assert all(d["revision"] == 3 for d in pending_entries)

    # Idempotent resubmit of Revision 3 returns the exact same deliveries snapshot.
    dup3_sub = make_submission(
        record_id=record_id,
        revision=3,
        text="第三版修订正文。",
        destination_ids=[
            dest_unclaimed.destination_id,
            dest_leased.destination_id,
            dest_acked.destination_id,
            dest_new.destination_id,
        ],
        source="user_edit",
    )
    dup3_resp = httpx.put(
        f"{world.gw.base_url}/v1/records/{record_id}/revisions/3",
        json=dup3_sub,
        headers={"Authorization": f"Bearer {world.device_token}"},
        timeout=5.0,
    )
    assert dup3_resp.status_code == 200
    assert_valid_contract("revision.schema.json#/$defs/stored", dup3_resp.json())
    assert dup3_resp.json()["duplicate"] is True
    assert dup3_resp.json()["deliveries"] == pub3["deliveries"]

    # dest_unclaimed only ever receives Revision 3, marked supersedes_revisions=[1].
    (claimed_unclaimed,) = agent_unclaimed.claim()["deliveries"]
    assert claimed_unclaimed["revision"] == 3
    assert claimed_unclaimed["supersedes_revisions"] == [1]
    assert agent_unclaimed.claim()["deliveries"] == []

    # dest_leased still holds its Revision 1 lease (can renew & ack) AND receives Revision 3.
    agent_leased.renew(leased_rev1["delivery_id"], leased_rev1["lease_token"])
    assert agent_leased.ack(leased_rev1["delivery_id"], leased_rev1["lease_token"])["state"] == "ACKNOWLEDGED"
    (claimed_leased_rev3,) = agent_leased.claim()["deliveries"]
    assert claimed_leased_rev3["revision"] == 3
    assert claimed_leased_rev3["supersedes_revisions"] == [1]

    # dest_acked keeps its Revision 1 ack AND receives Revision 3.
    repeat_ack = agent_acked.ack(acked_rev1["delivery_id"], acked_rev1["lease_token"])
    assert repeat_ack["duplicate"] is True
    assert repeat_ack["acknowledged_at"] == first_ack["acknowledged_at"]
    (claimed_acked_rev3,) = agent_acked.claim()["deliveries"]
    assert claimed_acked_rev3["revision"] == 3
    assert claimed_acked_rev3["supersedes_revisions"] == [1]

    # dest_new receives Revision 3 with supersedes_revisions=[1].
    (claimed_new_rev3,) = agent_new.claim()["deliveries"]
    assert claimed_new_rev3["revision"] == 3
    assert claimed_new_rev3["supersedes_revisions"] == [1]

    # dest_untouched was not targeted by Revision 3, so its Revision 1 delivery stays claimable.
    (claimed_untouched,) = agent_untouched.claim()["deliveries"]
    assert claimed_untouched["delivery_id"] == rev1_by_dest[dest_untouched.destination_id]
    assert claimed_untouched["revision"] == 1


def test_nack_retryable_and_skipped_destination_supersession(world: World) -> None:
    dest_a = world.destination("Dest A")
    dest_b = world.destination("Dest B")
    dest_c = world.destination("Dest C")
    agent_a = world.agent(dest_a)
    agent_b = world.agent(dest_b)
    agent_c = world.agent(dest_c)

    record_id = str(uuid.uuid4())
    world.publish(dest_a, dest_b, dest_c, record_id=record_id, revision=1, text="版本一。")

    # Dest A claims and retryable-nacks (back to PENDING). Dest B & C claim and ack rev 1.
    (a1,) = agent_a.claim()["deliveries"]
    agent_a.nack(a1["delivery_id"], a1["lease_token"], retryable=True, reason="稍后再试")

    (b1,) = agent_b.claim()["deliveries"]
    agent_b.ack(b1["delivery_id"], b1["lease_token"])

    (c1,) = agent_c.claim()["deliveries"]
    agent_c.ack(c1["delivery_id"], c1["lease_token"])

    # Revision 2 goes ONLY to Dest C (skips Dest A & Dest B). Dest C claims & acks rev 2.
    pub2 = world.publish(dest_c, record_id=record_id, revision=2, text="版本二。", source="user_edit")
    assert pub2["superseded_revisions"] == [1]
    assert [d["state"] for d in pub2["deliveries"]] == ["PENDING"]
    (c2,) = agent_c.claim()["deliveries"]
    assert c2["supersedes_revisions"] == [1]
    agent_c.ack(c2["delivery_id"], c2["lease_token"])

    # Revision 3 goes to Dest A, Dest B, Dest C.
    pub3 = world.publish(
        dest_a, dest_b, dest_c, record_id=record_id, revision=3, text="版本三。", source="user_edit"
    )
    assert pub3["superseded_revisions"] == [2]
    # Dest A's rev 1 (returned to PENDING via nack_retryable) is cancelled.
    cancelled_a = [d for d in pub3["deliveries"] if d["state"] == "CANCELLED"]
    assert len(cancelled_a) == 1
    assert cancelled_a[0]["delivery_id"] == a1["delivery_id"]
    assert cancelled_a[0]["revision"] == 1

    # Dest A and Dest B never saw rev 2, so rev 3 supersedes [1, 2] for them.
    (a3,) = agent_a.claim()["deliveries"]
    assert a3["revision"] == 3
    assert a3["supersedes_revisions"] == [1, 2]

    (b3,) = agent_b.claim()["deliveries"]
    assert b3["revision"] == 3
    assert b3["supersedes_revisions"] == [1, 2]

    # Dest C already saw rev 2 (which carried [1]), so rev 3 supersedes [2].
    (c3,) = agent_c.claim()["deliveries"]
    assert c3["revision"] == 3
    assert c3["supersedes_revisions"] == [2]


def test_expired_lease_delivery_is_cancelled_when_superseded(world: World) -> None:
    dest = world.destination("Expiry Supersede Agent")
    agent = world.agent(dest)
    record_id = str(uuid.uuid4())
    pub1 = world.publish(dest, record_id=record_id, revision=1, text="租约将过期的第一版。")
    rev1_id = pub1["deliveries"][0]["delivery_id"]

    (claimed1,) = agent.claim(lease_seconds=10)["deliveries"]
    time.sleep(10.3)

    pub2 = world.publish(
        dest, record_id=record_id, revision=2, text="租约过期后的第二版。", source="user_edit"
    )
    assert [d for d in pub2["deliveries"] if d["state"] == "CANCELLED"] == [
        {
            "delivery_id": rev1_id,
            "destination_id": dest.destination_id,
            "revision": 1,
            "state": "CANCELLED",
        }
    ]

    # Old lease cannot ack, and Agent only claims Revision 2.
    err = agent.call_error(
        "delivery_ack", {"delivery_id": rev1_id, "lease_token": claimed1["lease_token"]}
    )
    assert err["code"] in {"DELIVERY_LEASE_EXPIRED", "REQUEST_INVALID"}
    (claimed2,) = agent.claim()["deliveries"]
    assert claimed2["revision"] == 2
    assert claimed2["supersedes_revisions"] == [1]
