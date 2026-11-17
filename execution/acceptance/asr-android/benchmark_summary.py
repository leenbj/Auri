"""汇总 TranscribeFileActivity 写出的 run-report.json（Ticket 14 第 5 项）：先校验，再渲染成 Markdown 表格。

只有通过校验的报告才能进结果表：失败的运行、对不上 NFR-006 定义的 RTF、没测到的网络字节数都不能当证据。
报告里没有转录正文也没有路径；校验器对未知字段一律报错，防止以后有人把正文塞进报告。
证据等级由运行的人用 --evidence 标注（模拟器 E2、真机 E3），不写在报告里。

用法：python benchmark_summary.py --evidence "E2 模拟器" [--warm N] <标签>=<run-report.json> [...]
"""
import json
import pathlib
import statistics
import sys

TOP_KEYS = {"report_version", "final_state", "audio", "metrics", "process", "warm_inference_ms", "engine", "model", "device"}
SECTION_KEYS = {
    "audio": {"duration_ms", "sample_rate_hz", "channels"},
    "metrics": {"verify_ms", "engine_load_ms", "resample_ms", "inference_ms", "rtf"},
    "process": {"start_to_first_result_ms", "vm_hwm_kb_before", "vm_hwm_kb_after", "vm_rss_kb_after", "uid_network_bytes_delta"},
    "engine": {"version", "onnxruntime_version", "git_sha1", "num_threads"},
    "model": {"model_id", "model_version"},
    "device": {"model", "hardware", "fingerprint", "sdk_int", "abis"},
}
RTF_RELATIVE_TOLERANCE = 1e-6


def validate_report(report: dict, *, expect_warm_repeats: int | None = None) -> list[str]:
    problems: list[str] = []
    for key in sorted(set(report) - TOP_KEYS):
        problems.append(f"未知字段 {key}：报告不得带正文或其他未登记的内容")
    for key in sorted(TOP_KEYS - set(report)):
        problems.append(f"缺少字段 {key}")
    for section, keys in SECTION_KEYS.items():
        body = report.get(section)
        if not isinstance(body, dict):
            continue
        problems += [f"{section} 里未知字段 {k}" for k in sorted(set(body) - keys)]
        problems += [f"{section} 里缺少字段 {k}" for k in sorted(keys - set(body))]
    if problems:
        return problems

    if report["report_version"] != 1:
        problems.append(f"report_version 是 {report['report_version']}，校验器只认识 1")
    if report["final_state"] != "SUCCEEDED":
        problems.append(f"final_state 是 {report['final_state']}，只有 SUCCEEDED 的运行才能作为性能证据")

    duration, inference, rtf = report["audio"]["duration_ms"], report["metrics"]["inference_ms"], report["metrics"]["rtf"]
    if duration <= 0:
        problems.append(f"audio.duration_ms 必须为正，实际 {duration}")
    if inference <= 0:
        problems.append(f"metrics.inference_ms 必须为正，实际 {inference}：实测推理不可能是 0 毫秒")
    if "unavailable" in report["engine"].values():
        problems.append("engine 信息是 unavailable：原生库没能加载，这样的运行不是性能证据")
    if rtf is None:
        problems.append("metrics.rtf 缺失（null）：取不到就是取不到，不能当 0")
    elif duration > 0 and abs(rtf - inference / duration) > RTF_RELATIVE_TOLERANCE * max(rtf, 1e-12):
        problems.append(f"metrics.rtf={rtf} 与 inference_ms/duration_ms={inference / duration} 不符（NFR-006：推理时间 / 音频时长）")

    process = report["process"]
    if process["start_to_first_result_ms"] <= 0:
        problems.append(f"process.start_to_first_result_ms 必须为正，实际 {process['start_to_first_result_ms']}")
    for field in ("vm_hwm_kb_before", "vm_hwm_kb_after", "vm_rss_kb_after"):
        if process[field] is None:
            problems.append(f"process.{field} 没有读到（null），不能写进结果表")
    before, after = process["vm_hwm_kb_before"], process["vm_hwm_kb_after"]
    if before is not None and after is not None and after < before:
        problems.append(f"process.vm_hwm_kb_after={after} 小于 vm_hwm_kb_before={before}：历史峰值不可能下降")
    delta = process["uid_network_bytes_delta"]
    if delta is None:
        problems.append("process.uid_network_bytes_delta 没有测到（TrafficStats 不支持），不能作为离线证据")
    elif delta != 0:
        problems.append(f"process.uid_network_bytes_delta={delta}：离线转录期间应用不应收发任何字节")

    warm = report["warm_inference_ms"]
    if expect_warm_repeats is not None and len(warm) != expect_warm_repeats:
        problems.append(f"warm_inference_ms 有 {len(warm)} 项，期望 {expect_warm_repeats} 项")
    if any(v <= 0 for v in warm):
        problems.append(f"warm_inference_ms 里有不为正的值 {warm}：失败的运行不能记成 0 毫秒来拉低中位数")
    return problems


def _mib(kb: int) -> str:
    return f"{kb / 1024:.1f}"


def _row(label: str, report: dict) -> str:
    m, p = report["metrics"], report["process"]
    warm = ", ".join(str(v) for v in report["warm_inference_ms"]) or "—"
    cells = [
        label, str(report["audio"]["duration_ms"]), str(p["start_to_first_result_ms"]), str(m["verify_ms"]), str(m["engine_load_ms"]),
        str(m["inference_ms"]), f"{m['rtf']:.4f}", warm, _mib(p["vm_hwm_kb_after"]), _mib(p["vm_rss_kb_after"]), str(p["uid_network_bytes_delta"]),
    ]
    return "| " + " | ".join(cells) + " |"


def render_table(reports: dict[str, dict], *, evidence: str) -> str:
    """每份报告一行，最后加一行中位数（冷启动、各阶段耗时、RTF、峰值内存取中位数）。设备指纹不进表。"""
    first = next(iter(reports.values()))
    header = [
        "运行", "音频 ms", "冷启动 ms（进程启动→首个结果）", "校验 ms", "引擎加载 ms", "推理 ms", "RTF",
        "同引擎再推理 ms", "VmHWM MiB", "VmRSS MiB", "网络字节增量",
    ]
    lines = [
        f"证据等级：{evidence}；引擎 {first['engine']['version']}（ONNX Runtime {first['engine']['onnxruntime_version']}），"
        f"线程数 {first['engine']['num_threads']}，模型 {first['model']['model_id']} {first['model']['model_version']}，"
        f"设备 {first['device']['model']}（hardware={first['device']['hardware']}，SDK {first['device']['sdk_int']}）",
        "",
        "| " + " | ".join(header) + " |",
        "|" + "|".join(["---"] * len(header)) + "|",
    ]
    lines += [_row(label, report) for label, report in reports.items()]

    def med(getter) -> float:
        return statistics.median(getter(r) for r in reports.values())

    lines.append(
        "| 中位数 | {:g} | {:g} | {:g} | {:g} | {:g} | {:.4f} | — | {} | {} | — |".format(
            med(lambda r: r["audio"]["duration_ms"]), med(lambda r: r["process"]["start_to_first_result_ms"]),
            med(lambda r: r["metrics"]["verify_ms"]), med(lambda r: r["metrics"]["engine_load_ms"]),
            med(lambda r: r["metrics"]["inference_ms"]), med(lambda r: r["metrics"]["rtf"]),
            _mib(med(lambda r: r["process"]["vm_hwm_kb_after"])), _mib(med(lambda r: r["process"]["vm_rss_kb_after"])),
        )
    )
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    args, evidence, warm, pairs = argv[1:], None, None, []
    while args:
        arg = args.pop(0)
        if arg == "--evidence" and args:
            evidence = args.pop(0)
        elif arg == "--warm" and args:
            warm = int(args.pop(0))
        elif "=" in arg:
            pairs.append(arg.split("=", 1))
        else:
            print(__doc__, file=sys.stderr)
            return 2
    if evidence is None or not pairs:
        print(__doc__, file=sys.stderr)
        return 2

    reports, problems = {}, []
    for label, path in pairs:
        reports[label] = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
        problems += [f"[{label}] {p}" for p in validate_report(reports[label], expect_warm_repeats=warm)]
    for p in problems:
        print(p)
    if problems:
        return 1
    print(render_table(reports, evidence=evidence))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
