"""Deleting a Record hides it from Agent reads while preserving Delivery status."""
from __future__ import annotations

import uuid

import httpx

from harness import World, assert_valid_contract, gateway, world  # noqa: F401


def test_deleted_record_is_hidden_from_agent_reads_but_preserves_acknowledgement(
    world: World,
) -> None:
    destination = world.destination("Reader Agent")
    agent = world.agent(destination)
    phrase = "unique cross-ticket phrase"
    stored = world.publish(destination, text=f"Remember this {phrase} for later.")
    record_id = stored["record_id"]
    delivery_id = stored["deliveries"][0]["delivery_id"]

    (delivery,) = agent.claim()["deliveries"]
    assert delivery["delivery_id"] == delivery_id
    acknowledgement = agent.ack(delivery_id, delivery["lease_token"])
    assert acknowledgement["state"] == "ACKNOWLEDGED"
    assert [record["record_id"] for record in agent.record_list()["records"]] == [record_id]
    assert phrase in agent.record_get(record_id)["text"]
    assert [match["record_id"] for match in agent.record_search(phrase)["matches"]] == [record_id]

    headers = {"Authorization": f"Bearer {world.device_token}"}
    deleted = httpx.delete(
        f"{world.gw.base_url}/v1/records/{record_id}", headers=headers, timeout=5.0
    )
    assert deleted.status_code == 200, deleted.text
    assert_valid_contract("revision.schema.json#/$defs/record_deleted", deleted.json())
    assert deleted.json()["record_id"] == record_id

    assert agent.record_list()["records"] == []
    get_request = {"record_id": record_id}
    assert_valid_contract("record_query.schema.json#/$defs/get_request", get_request)
    deleted_error = agent.call_error("record_get", get_request)
    missing_error = agent.call_error("record_get", {"record_id": str(uuid.uuid4())})
    assert deleted_error["code"] == "REQUEST_INVALID"
    assert deleted_error["retryable"] is False
    assert (deleted_error["code"], deleted_error["message"]) == (
        missing_error["code"], missing_error["message"]
    )
    assert agent.record_search(phrase)["matches"] == []

    status = httpx.get(
        f"{world.gw.base_url}/v1/records/{record_id}/status", headers=headers, timeout=5.0
    )
    assert status.status_code == 200, status.text
    body = status.json()
    assert_valid_contract("gateway.schema.json#/$defs/record_status_response", body)
    assert body["record_id"] == record_id
    assert body["deleted"] is True
    (destination_status,) = body["destinations"]
    assert destination_status["destination_id"] == destination.destination_id
    assert destination_status["delivery_id"] == delivery_id
    assert destination_status["delivery_state"] == "ACKNOWLEDGED"
    assert destination_status["acknowledged_at"] == acknowledgement["acknowledged_at"]


def test_superseded_unclaimed_revision_is_readable_until_record_deletion(
    world: World,
) -> None:
    destination = world.destination("Revision Reader")
    agent = world.agent(destination)
    first_text = "Original cross-ticket revision."
    first = world.publish(destination, text=first_text)
    record_id = first["record_id"]
    assert first["deliveries"][0]["state"] == "PENDING"

    second_text = "Updated cross-ticket revision."
    world.publish(
        destination,
        record_id=record_id,
        revision=2,
        text=second_text,
        source="user_edit",
    )

    older = agent.record_get(record_id, revision=1)
    assert older["revision"] == 1
    assert older["text"] == first_text
    assert older["delivery_state"] == "CANCELLED"
    latest = agent.record_get(record_id)
    assert latest["revision"] == 2
    assert latest["text"] == second_text
    assert [(record["record_id"], record["revision"]) for record in agent.record_list()["records"]] == [
        (record_id, 2)
    ]

    headers = {"Authorization": f"Bearer {world.device_token}"}
    deleted = httpx.delete(
        f"{world.gw.base_url}/v1/records/{record_id}", headers=headers, timeout=5.0
    )
    assert deleted.status_code == 200, deleted.text
    assert_valid_contract("revision.schema.json#/$defs/record_deleted", deleted.json())
    assert deleted.json()["record_id"] == record_id

    request = {"record_id": record_id, "revision": 1}
    assert_valid_contract("record_query.schema.json#/$defs/get_request", request)
    deleted_error = agent.call_error("record_get", request)
    missing_error = agent.call_error("record_get", {"record_id": str(uuid.uuid4()), "revision": 1})
    assert deleted_error["code"] == "REQUEST_INVALID"
    assert deleted_error["retryable"] is False
    assert (deleted_error["code"], deleted_error["message"]) == (
        missing_error["code"], missing_error["message"]
    )
