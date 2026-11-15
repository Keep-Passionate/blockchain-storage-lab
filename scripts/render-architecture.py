#!/usr/bin/env python3
"""文件功能：用 Matplotlib 重绘四层业务架构图，输出可复用的 PNG 与 SVG。

图中语义对应 docs/four-layer-architecture.mmd；本脚本手动编排布局，不解析 Mermaid。
修改业务图时应同步修改两份图源。无网络请求，不读取服务器或实验私有数据。
"""

from pathlib import Path
import argparse

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch


ROOT = Path(__file__).resolve().parents[1]
INK = "#1F3445"
MUTED = "#536B7C"
LINE = "#647D8D"
BLUE = "#EAF2FC"
GREEN = "#EAF5EF"
AMBER = "#FFF3DE"
PURPLE = "#F0ECF8"
GREY = "#F1F4F6"


def configure_font(explicit_path):
    """优先使用指定中文字体，再探测 Windows / Linux 常见字体。"""
    candidates = [explicit_path] if explicit_path else []
    candidates += [
        "C:/Windows/Fonts/msyh.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            font_manager.fontManager.addfont(candidate)
            family = font_manager.FontProperties(fname=candidate).get_name()
            plt.rcParams["font.family"] = family
            break
    else:
        raise SystemExit("未找到中文字体；请用 --font 指定可用的 TTF/TTC/OTF 文件。")
    plt.rcParams.update({"svg.fonttype": "path", "axes.unicode_minus": False})


def render(output_directory):
    """按业务关系、存储分工、事件恢复、Fabric 步骤四个阅读区绘图。"""
    fig, ax = plt.subplots(figsize=(16, 11), facecolor="white")
    fig.subplots_adjust(left=0, right=1, bottom=0, top=1)
    ax.set(xlim=(0, 16), ylim=(0, 11))
    ax.axis("off")

    def text(x, y, value, size=11, color=INK, weight="normal", **kwargs):
        ax.text(x, y, value, fontsize=size, color=color, weight=weight,
                ha=kwargs.pop("ha", "left"), va=kwargs.pop("va", "center"), **kwargs)

    def card(x, y, width, height, title, detail, color=BLUE, size=11.2):
        ax.add_patch(FancyBboxPatch((x, y), width, height,
                     boxstyle="round,pad=0.015,rounding_size=0.09",
                     linewidth=1.0, edgecolor="#B9C9D5", facecolor=color, zorder=2))
        text(x + width / 2, y + height - 0.24, title, size + 1, weight="bold", ha="center", zorder=3)
        text(x + width / 2, y + (height - 0.3) / 2 - 0.05, detail, size,
             ha="center", linespacing=1.6, zorder=3)

    def arrow(start, end, *, both=False, color=LINE, style="solid", rad=0):
        ax.add_patch(FancyArrowPatch(start, end, arrowstyle="<->" if both else "-|>",
                     mutation_scale=12, linewidth=1.5, color=color, linestyle=style,
                     connectionstyle=f"arc3,rad={rad}", shrinkA=3, shrinkB=3, zorder=1))

    def routed(points, color=LINE):
        # 最后一段带箭头，其余段保持直角布局，避免穿过卡片。
        for first, second in zip(points[:-2], points[1:-1]):
            ax.plot([first[0], second[0]], [first[1], second[1]], color=color, lw=1.4, zorder=1)
        arrow(points[-2], points[-1], color=color)

    text(0.5, 10.55, "四层溯源：业务事实、链下证据与可恢复的数据流", 21, weight="bold")
    text(0.5, 10.15, "当前实现 · Fabric 单 Channel + IPFS + MySQL · 全部演示输入标记 synthetic=true", 11.5, MUTED)

    # 四层是对象关系；避免画成四条链或必须依次发生的四个处理阶段。
    card(0.5, 8.95, 3.25, 0.95, "批次  batch", "区域代码－年份－六位序号", BLUE)
    card(4.25, 8.95, 3.25, 0.95, "地块  plot", "品种 / 生育期 / 海拔 / 环境", GREEN)
    card(8.0, 8.95, 3.25, 0.95, "操作  operation", "种植 / 灌溉 / 采收等 6 类", BLUE)
    card(11.75, 8.95, 3.75, 0.95, "检测  inspection", "结论 + 报告 CID + SHA-256", PURPLE)
    text(0.5, 8.62, "关系：每个批次引用一个不可变地块快照；操作、检测归属批次；审核、审计、预警另行追加。", 11.4, MUTED)

    # 主链路：只有有效提交确认后，应用才将请求记为已上链。
    card(0.5, 7.2, 2.0, 1.0, "虚拟场景 / CLI", "结构化录入", GREY)
    card(3.05, 7.2, 3.1, 1.0, "Java 业务服务", "规则校验 / 原因码 / 回执", BLUE)
    card(10.5, 7.2, 5.0, 1.0, "Fabric：有效提交的业务事实", "7 类不可变记录；可追溯审核与审计", BLUE)
    arrow((2.5, 7.7), (3.05, 7.7))
    arrow((6.15, 7.7), (10.5, 7.7))
    text(8.3, 7.96, "通过 → 背书 / 排序 / 验证提交", 10.8, ha="center")
    text(8.3, 7.43, "链码复核身份、引用与业务约束", 10.1, MUTED, ha="center")

    # MySQL 业务日志不可由链上恢复；投影才是可重建副本。
    card(0.5, 5.2, 4.3, 1.35, "MySQL：提交业务日志", "p_submission：原始输入 / 状态 / 原因\n含拒绝操作的链下独有证据，需备份", AMBER)
    card(5.3, 5.2, 4.7, 1.35, "IPFS：报告与审计证据", "add / pin → CID；原始字节 → SHA-256\n取回时重新计算摘要，与链上记录比较", PURPLE)
    card(10.5, 5.2, 5.0, 1.35, "MySQL：七类查询投影", "record_json + fabric_tx_id + 查询字段\n从已提交链上记录重放，可重建", GREEN)
    arrow((3.65, 7.2), (2.8, 6.55), color="#A47630")
    text(1.3, 6.85, "拒绝也留证；不写入链上操作", 10, "#8A641F")
    arrow((5.75, 7.2), (6.45, 6.55), both=True, color="#83709E")
    text(6.7, 6.84, "原件 / CID / SHA-256", 10.1, "#675383")
    arrow((13.0, 7.2), (13.0, 6.55))
    text(13.2, 6.84, "读取 / 重放", 10.1, MUTED)

    # 事件收件与补偿扫描分开表达，不把收件成功画成已送达真实通知。
    card(10.5, 3.2, 5.0, 1.35, "真实事件订阅 → MySQL 收件", "有限时重放；txID + eventName 去重\n从最近区块开始恢复，包含该区块", GREEN)
    card(5.3, 3.2, 4.7, 1.35, "按批次补偿扫描", "来源：FAIL 检测 / FLAGGED 审计\n预警 ID = EV-来源交易 ID；已有则跳过", GREY)
    card(0.5, 3.2, 4.3, 1.35, "追加模拟预警  alert", "deliveryStatus = SIMULATED\n由 Java 再提交 Fabric；不发送真实消息", AMBER)
    routed([(15.5, 7.7), (15.8, 7.7), (15.8, 3.88), (15.5, 3.88)])
    arrow((10.5, 3.88), (10.0, 3.88))
    arrow((5.3, 3.88), (4.8, 3.88))
    text(0.5, 4.86, "事件与恢复：收件成功后仍检查缺失预警；重复重放与重复扫描不会重复追加同一条预警。", 10.7, MUTED)

    # 同一 Gateway 在时间顺序中出现两次，让广播责任与背书责任清楚可见。
    text(0.5, 2.68, "Fabric 提交展开：Gateway 协调，Peer 执行和验证，Orderer 不检查业务规则", 12.8, weight="bold")
    stages = [
        (0.5, 2.4, "1  Gateway", "请求背书"),
        (3.5, 2.4, "2  背书 Peer", "模拟执行 → 读写集"),
        (6.5, 2.4, "3  Gateway", "广播已签名背书交易"),
        (9.5, 2.4, "4  Orderer", "排序并生成区块"),
        (12.5, 3.0, "5  提交 Peer", "验证背书与版本 → 账本"),
    ]
    for x, width, title, detail in stages:
        card(x, 1.3, width, 1.0, title, detail, BLUE, size=10.7)
    for first, second in zip(stages[:-1], stages[1:]):
        arrow((first[0] + first[1], 1.8), (second[0], 1.8))
    text(0.5, 0.93, "这里两次出现的是同一 Gateway 的两个步骤；客户端负责签名，最终以有效提交状态确认写入。", 10.5, MUTED)
    text(0.5, 0.43, "实验边界：规则与数据是虚拟的；Org1 / Org2 仅演示职责；尚无移动端、真实通知、自动月审或正式质量认证。", 10.4, MUTED)

    output_directory.mkdir(parents=True, exist_ok=True)
    for extension in ("png", "svg"):
        output = output_directory / f"four-layer-architecture.{extension}"
        fig.savefig(output, dpi=180, facecolor="white", metadata={"Title": "四层溯源系统架构"})
        if extension == "svg":
            # Matplotlib 路径行末带空格，规范化便于 Git 检查；不改变矢量几何。
            output.write_text("\n".join(line.rstrip() for line in output.read_text(encoding="utf-8").splitlines()) + "\n", encoding="utf-8", newline="\n")
        print(output)
    plt.close(fig)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", help="可选中文字体文件路径")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "docs")
    args = parser.parse_args()
    configure_font(args.font)
    render(args.output_dir)
