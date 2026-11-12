/** 文件功能：按业务阶段分组事件，用于第一篇论文模块的应用层实验。 */
package lab.blockchain.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Application-layer experiment inspired by the first paper's stage grouping.
 * It groups events before appending; it is deliberately not a Fabric consensus
 * plugin and must not be described as SHT-PBFT itself.
 */
public final class StageBatcher {
    public Map<Stage, List<TraceEvent>> group(List<TraceEvent> events) {
        Map<Stage, List<TraceEvent>> grouped = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            grouped.put(stage, events.stream().filter(event -> event.stage() == stage).toList());
        }
        return grouped;
    }
}
