import type { ProbeLogRecord } from './ProbeTypes';

const MAC_PATTERN = /([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}/g;

const MAC_KEYS: string[] = ['address', 'mac', 'mac_address', 'bt_address'];
const DEVICE_NAME_KEYS: string[] = ['display_name', 'device_name', 'name', 'service_name', 'host_name'];
const SECRET_OR_CONTENT_KEYS: string[] = [
  'text',
  'transcript',
  'audio_bytes',
  'pcm_base64',
  'token',
  'secret',
  'password',
  'credential'
];

export function redactMacTail(address: string): string {
  const trimmed = address.trim();
  if (trimmed.length === 0) {
    return '';
  }
  const tailLength = trimmed.length >= 5 ? 5 : trimmed.length;
  return `**..${trimmed.slice(trimmed.length - tailLength)}`;
}

export function redactDeviceName(name: string): string {
  if (name.trim().length === 0) {
    return '';
  }
  return '[REDACTED_DEVICE]';
}

function redactInlineMacs(value: string): string {
  return value.replace(MAC_PATTERN, (match: string): string => redactMacTail(match));
}

function redactScalarOrNested(
  key: string,
  val: string | number | boolean | null | undefined | Array<string> | Array<Record<string, string | number | boolean | null>>
): string | number | boolean | null | undefined | Array<string> | Array<Record<string, string | number | boolean | null>> {
  const lowerKey = key.toLowerCase();

  if (typeof val === 'string') {
    if (MAC_KEYS.includes(lowerKey)) {
      return redactMacTail(val);
    }
    if (DEVICE_NAME_KEYS.includes(lowerKey)) {
      return redactDeviceName(val);
    }
    if (SECRET_OR_CONTENT_KEYS.includes(lowerKey)) {
      return '[REDACTED]';
    }
    return redactInlineMacs(val);
  }

  if (Array.isArray(val)) {
    const outStrings: string[] = [];
    const outObjects: Array<Record<string, string | number | boolean | null>> = [];
    let isObjectArray = false;

    for (let i = 0; i < val.length; i += 1) {
      const item = val[i];
      if (typeof item === 'string') {
        outStrings.push(redactInlineMacs(item));
      } else if (item !== null && typeof item === 'object') {
        isObjectArray = true;
        outObjects.push(redactFlatRecord(item));
      }
    }
    return isObjectArray ? outObjects : outStrings;
  }

  return val;
}

function redactFlatRecord(
  input: Record<string, string | number | boolean | null>
): Record<string, string | number | boolean | null> {
  const out: Record<string, string | number | boolean | null> = {};
  const keys = Object.keys(input);
  for (let i = 0; i < keys.length; i += 1) {
    const key = keys[i];
    const val = input[key];
    if (typeof val === 'string') {
      const lowerKey = key.toLowerCase();
      if (MAC_KEYS.includes(lowerKey)) {
        out[key] = redactMacTail(val);
      } else if (DEVICE_NAME_KEYS.includes(lowerKey)) {
        out[key] = redactDeviceName(val);
      } else if (SECRET_OR_CONTENT_KEYS.includes(lowerKey)) {
        out[key] = '[REDACTED]';
      } else {
        out[key] = redactInlineMacs(val);
      }
    } else {
      out[key] = val;
    }
  }
  return out;
}

export function redactLogRecord(record: ProbeLogRecord): ProbeLogRecord {
  const sanitizedData: Record<
    string,
    string | number | boolean | null | undefined | Array<string> | Array<Record<string, string | number | boolean | null>>
  > = {};

  const keys = Object.keys(record.data);
  for (let i = 0; i < keys.length; i += 1) {
    const key = keys[i];
    sanitizedData[key] = redactScalarOrNested(key, record.data[key]);
  }

  return {
    seq: record.seq,
    launch_id: record.launch_id,
    mono_ns: record.mono_ns,
    wall: record.wall,
    kind: record.kind,
    app_state: record.app_state,
    data: sanitizedData
  };
}

export function redactLogLineJson(rawLine: string): string {
  try {
    const parsed = JSON.parse(rawLine) as ProbeLogRecord;
    if (!parsed || typeof parsed.kind !== 'string' || typeof parsed.data !== 'object' || parsed.data === null) {
      return JSON.stringify({ kind: 'redact_parse_failed' });
    }
    return JSON.stringify(redactLogRecord(parsed));
  } catch {
    return JSON.stringify({ kind: 'redact_parse_failed' });
  }
}
