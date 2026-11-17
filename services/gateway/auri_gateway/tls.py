"""Self-signed TLS key/certificate lifecycle and SPKI SHA-256 fingerprint helpers (ADR-0018)."""
from __future__ import annotations

import hashlib
import ipaddress
import os
import pathlib
import socket
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, PublicFormat
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

from .db import ensure_gateway_initialized, format_utc, open_connection
from .network import detect_lan_ips

DEFAULT_CERT_VALIDITY_DAYS = 365


@dataclass(frozen=True)
class TlsMaterial:
    key_path: pathlib.Path
    cert_path: pathlib.Path
    public_key_sha256: str
    serial_number: int
    not_valid_before: datetime
    not_valid_after: datetime


def tls_paths(data_dir: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path]:
    tls_dir = data_dir / "tls"
    return tls_dir / "gateway.key.pem", tls_dir / "gateway.cert.pem"


def spki_sha256_from_public_key(public_key: object) -> str:
    """Return lowercase SHA-256 hex of DER-encoded SubjectPublicKeyInfo (ADR-0018)."""
    spki_der = public_key.public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)  # type: ignore[attr-defined]
    return hashlib.sha256(spki_der).hexdigest()


def spki_sha256_from_cert_der(der_bytes: bytes) -> str:
    cert = x509.load_der_x509_certificate(der_bytes)
    return spki_sha256_from_public_key(cert.public_key())


def _atomic_write_bytes(path: pathlib.Path, data: bytes, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    fd = os.open(tmp_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(tmp_path, mode)
        os.replace(tmp_path, path)
    except Exception:
        try:
            tmp_path.unlink(missing_ok=True)
        except OSError:
            pass
        raise


def _load_gateway_id(data_dir: pathlib.Path) -> str:
    db_path = ensure_gateway_initialized(data_dir)
    conn = open_connection(db_path)
    try:
        row = conn.execute("SELECT gateway_id FROM gateway_meta WHERE id = 1").fetchone()
        assert row is not None
        return str(row["gateway_id"])
    finally:
        conn.close()


def _build_san_entries(san_ips: list[str] | None) -> list[x509.GeneralName]:
    entries: list[x509.GeneralName] = []
    seen_dns: set[str] = set()
    seen_ips: set[str] = set()

    hostname = socket.gethostname()
    dns_candidates = ["localhost"]
    if hostname:
        dns_candidates.append(hostname)
        if not hostname.endswith(".local"):
            dns_candidates.append(f"{hostname}.local")
    for dns in dns_candidates:
        if dns not in seen_dns:
            seen_dns.add(dns)
            entries.append(x509.DNSName(dns))

    ip_candidates = ["127.0.0.1", "::1", *(san_ips or [])]
    for raw_ip in ip_candidates:
        try:
            ip_obj = ipaddress.ip_address(raw_ip)
        except ValueError:
            continue
        canonical = str(ip_obj)
        if canonical not in seen_ips:
            seen_ips.add(canonical)
            entries.append(x509.IPAddress(ip_obj))

    return entries


def _issue_self_signed_cert(
    private_key: ec.EllipticCurvePrivateKey,
    *,
    gateway_id: str,
    san_ips: list[str] | None,
    validity_days: int,
) -> x509.Certificate:
    if validity_days < 1:
        raise ValueError("validity_days must be at least 1")
    now = datetime.now(timezone.utc)
    not_before = now - timedelta(minutes=5)
    not_after = now + timedelta(days=validity_days)
    public_key = private_key.public_key()
    subject = x509.Name(
        [
            x509.NameAttribute(NameOID.COMMON_NAME, f"Auri Gateway ({gateway_id})"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Auri Self-Hosted Gateway"),
        ]
    )
    builder = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(public_key)
        .serial_number(x509.random_serial_number())
        .not_valid_before(not_before)
        .not_valid_after(not_after)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True,
                content_commitment=False,
                key_encipherment=False,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=False,
                crl_sign=False,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(
            x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),
            critical=False,
        )
        .add_extension(
            x509.SubjectKeyIdentifier.from_public_key(public_key),
            critical=False,
        )
        .add_extension(
            x509.SubjectAlternativeName(_build_san_entries(san_ips)),
            critical=False,
        )
    )
    return builder.sign(private_key=private_key, algorithm=hashes.SHA256())


def _load_private_key(key_path: pathlib.Path) -> ec.EllipticCurvePrivateKey:
    key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey):
        raise ValueError("Gateway TLS private key must be an EC private key.")
    return key


def _material_from_cert(
    key_path: pathlib.Path, cert_path: pathlib.Path, cert: x509.Certificate
) -> TlsMaterial:
    return TlsMaterial(
        key_path=key_path,
        cert_path=cert_path,
        public_key_sha256=spki_sha256_from_public_key(cert.public_key()),
        serial_number=cert.serial_number,
        not_valid_before=cert.not_valid_before_utc,
        not_valid_after=cert.not_valid_after_utc,
    )


def ensure_tls_material(
    data_dir: pathlib.Path,
    *,
    san_ips: list[str] | None = None,
    validity_days: int = DEFAULT_CERT_VALIDITY_DAYS,
) -> TlsMaterial:
    """Create TLS private key and self-signed cert on first run, reusing existing key thereafter."""
    gateway_id = _load_gateway_id(data_dir)
    key_path, cert_path = tls_paths(data_dir)
    key_path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(key_path.parent, 0o700)

    if key_path.exists():
        private_key = _load_private_key(key_path)
    else:
        private_key = ec.generate_private_key(ec.SECP256R1())
        key_pem = private_key.private_bytes(
            encoding=Encoding.PEM,
            format=PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
        _atomic_write_bytes(key_path, key_pem, 0o600)

    if cert_path.exists():
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        expected_fp = spki_sha256_from_public_key(private_key.public_key())
        cert_fp = spki_sha256_from_public_key(cert.public_key())
        if cert_fp != expected_fp:
            raise ValueError("Existing gateway certificate public key does not match private key.")
        return _material_from_cert(key_path, cert_path, cert)

    cert = _issue_self_signed_cert(
        private_key,
        gateway_id=gateway_id,
        san_ips=san_ips if san_ips is not None else detect_lan_ips(),
        validity_days=validity_days,
    )
    _atomic_write_bytes(cert_path, cert.public_bytes(Encoding.PEM), 0o644)
    return _material_from_cert(key_path, cert_path, cert)


def renew_certificate(
    data_dir: pathlib.Path,
    *,
    san_ips: list[str] | None = None,
    validity_days: int = DEFAULT_CERT_VALIDITY_DAYS,
) -> dict[str, object]:
    """Re-issue a self-signed certificate using the existing private key (preserving SPKI fingerprint)."""
    gateway_id = _load_gateway_id(data_dir)
    key_path, cert_path = tls_paths(data_dir)
    if not key_path.exists():
        raise FileNotFoundError(
            f"No existing TLS private key at {key_path}; start gateway or run pair first."
        )
    private_key = _load_private_key(key_path)
    cert = _issue_self_signed_cert(
        private_key,
        gateway_id=gateway_id,
        san_ips=san_ips if san_ips is not None else detect_lan_ips(),
        validity_days=validity_days,
    )
    _atomic_write_bytes(cert_path, cert.public_bytes(Encoding.PEM), 0o644)
    material = _material_from_cert(key_path, cert_path, cert)
    return {
        "gateway_id": gateway_id,
        "public_key_sha256": material.public_key_sha256,
        "serial_number": str(material.serial_number),
        "not_valid_before": format_utc(material.not_valid_before),
        "not_valid_after": format_utc(material.not_valid_after),
        "cert_path": str(material.cert_path),
    }
