"""Contract version 1.2 on the wire (Ticket 22 re-review R7).

The Destination resource gained `credential_active`, a delivery-additions endpoint and several gateway rules
(suspension, revocation cutoff, the 50-Destination limit). Resource schemas forbid extra properties, so a strict 1.1
client would reject those responses: the contract moved to 1.2 instead of pretending to be a compatible 1.1.

The gateway therefore advertises exactly one version and refuses requests that still say `1.1` — loudly (422) and
without side effects, never half-applied. The phone relies on the advertisement to detect an old gateway before it
sends anything (apps/android `ContractGuard`); the other direction is what these tests pin.
"""

from __future__ import annotations

import httpx

from harness import GatewayProcess, assert_valid_contract, gateway, make_redeem_request, make_submission

PREVIOUS = "1.1"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _assert_refused(resp: httpx.Response) -> None:
    assert resp.status_code == 422, resp.text
    assert_valid_contract("error.schema.json", resp.json())
    assert resp.json()["code"] == "REQUEST_INVALID"


def test_gateway_advertises_exactly_the_current_contract_version(gateway: GatewayProcess) -> None:
    paired = gateway.pair_device("Version Phone")
    resp = httpx.get(f"{gateway.base_url}/v1/capabilities", trust_env=False, headers=_auth(paired["device_token"]), timeout=5.0)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert_valid_contract("gateway.schema.json#/$defs/capabilities_response", body)
    # 只通告一个：手机据此判断“网关说不说我要的版本”，多报一个旧版本就等于承诺了做不到的事
    assert body["schema_version"] == "1.2"
    assert body["supported_schema_versions"] == ["1.2"]


def test_pairing_payload_and_redeem_response_carry_the_current_version(gateway: GatewayProcess) -> None:
    result = gateway.run_cli("pair", "--data-dir", str(gateway.data_dir), "--json")
    assert result.returncode == 0, result.stderr
    import json

    payload = json.loads(result.stdout)
    assert payload["schema_version"] == "1.2"
    assert_valid_contract("pairing.schema.json#/$defs/payload", payload)
    redeemed = httpx.post(
        f"{gateway.base_url}/v1/pairing/redeem",
        trust_env=False,
        json=make_redeem_request(payload["pairing_code"]),
        timeout=5.0,
    )
    assert redeemed.status_code == 200, redeemed.text
    assert redeemed.json()["schema_version"] == "1.2"


def test_requests_that_still_say_the_previous_version_are_refused_and_change_nothing(gateway: GatewayProcess) -> None:
    paired = gateway.pair_device("Old Client Phone")
    token = paired["device_token"]
    dest = gateway.create_agent_destination(token, "Version Agent")
    destination_ids = [dest.destination_id]

    # 1) 入库：被拒，网关上没有这条 Record
    submission = make_submission(destination_ids=destination_ids)
    submission["schema_version"] = PREVIOUS
    put = httpx.put(
        f"{gateway.base_url}/v1/records/{submission['record_id']}/revisions/1",
        trust_env=False, json=submission, headers=_auth(token), timeout=5.0,
    )
    _assert_refused(put)
    status = httpx.get(f"{gateway.base_url}/v1/records/{submission['record_id']}/status", trust_env=False, headers=_auth(token), timeout=5.0)
    assert status.status_code == 404, status.text

    # 2) 追加 Delivery：被拒，这一版上仍然没有给这个 Destination 的投递
    stored = gateway.publish(token, [])
    record_id = stored["record_id"]
    additions = {"schema_version": PREVIOUS, "intent": "context", "destination_ids": destination_ids}
    added = httpx.post(
        f"{gateway.base_url}/v1/records/{record_id}/revisions/1/deliveries",
        trust_env=False, json=additions, headers=_auth(token), timeout=5.0,
    )
    _assert_refused(added)
    after = httpx.get(f"{gateway.base_url}/v1/records/{record_id}/status", trust_env=False, headers=_auth(token), timeout=5.0).json()
    assert after["destinations"] == []

    # 3) 创建 Destination：被拒，列表里没有多出来
    before_count = len(httpx.get(f"{gateway.base_url}/v1/destinations", trust_env=False, headers=_auth(token), timeout=5.0).json()["destinations"])
    create = {
        "schema_version": PREVIOUS, "display_name": "旧版本创建", "send_mode": "auto_send", "allowed_intents": ["context"],
    }
    _assert_refused(httpx.post(f"{gateway.base_url}/v1/destinations", trust_env=False, json=create, headers=_auth(token), timeout=5.0))
    after_count = len(httpx.get(f"{gateway.base_url}/v1/destinations", trust_env=False, headers=_auth(token), timeout=5.0).json()["destinations"])
    assert after_count == before_count

    # 4) 配对：被拒，而且配对码没被消耗——更新手机之后用同一个码还能配
    code = gateway.issue_pairing_code()
    old_redeem = make_redeem_request(code)
    old_redeem["schema_version"] = PREVIOUS
    _assert_refused(httpx.post(f"{gateway.base_url}/v1/pairing/redeem", trust_env=False, json=old_redeem, timeout=5.0))
    again = httpx.post(f"{gateway.base_url}/v1/pairing/redeem", trust_env=False, json=make_redeem_request(code), timeout=5.0)
    assert again.status_code == 200, again.text
