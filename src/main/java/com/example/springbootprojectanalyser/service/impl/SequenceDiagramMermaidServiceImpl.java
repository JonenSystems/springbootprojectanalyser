package com.example.springbootprojectanalyser.service.impl;

import com.example.springbootprojectanalyser.model.dto.DbNoteDto;
import com.example.springbootprojectanalyser.model.dto.FlowDto;
import com.example.springbootprojectanalyser.model.dto.ParticipantDto;
import com.example.springbootprojectanalyser.model.dto.SequenceInputDto;
import com.example.springbootprojectanalyser.model.dto.EntryPointDto;
import com.example.springbootprojectanalyser.service.SequenceDiagramMermaidService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SequenceInputDto を Mermaid の sequenceDiagram 文字列へ変換するサービス実装。
 */
@Service
public class SequenceDiagramMermaidServiceImpl implements SequenceDiagramMermaidService {

    @Override
    public String generateSequenceDiagramText(SequenceInputDto sequenceInput) {
        if (sequenceInput == null) {
            throw new IllegalArgumentException("sequenceInput is null");
        }

        List<ParticipantDto> participants = sequenceInput.participants() != null
            ? sequenceInput.participants()
            : List.of();
        List<FlowDto> flows = sequenceInput.flows() != null ? sequenceInput.flows() : List.of();
        List<String> participantOrder = sequenceInput.participantOrder() != null
            ? sequenceInput.participantOrder()
            : participants.stream().map(ParticipantDto::id).toList();

        Map<String, ParticipantDto> participantById = new HashMap<>();
        for (ParticipantDto p : participants) {
            if (p == null || p.id() == null) {
                continue;
            }
            participantById.put(p.id(), p);
        }

        // Mermaid の participant/actor に使用する識別子（英数/underscore のみに制限）
        Map<String, String> mermaidIdByOriginalId = new HashMap<>();
        for (String originalId : participantOrder) {
            if (originalId == null) {
                continue;
            }
            String mermaidId = sanitizeParticipantId(originalId);
            mermaidIdByOriginalId.put(originalId, mermaidId);
        }
        for (ParticipantDto p : participants) {
            if (p == null || p.id() == null) {
                continue;
            }
            mermaidIdByOriginalId.putIfAbsent(p.id(), sanitizeParticipantId(p.id()));
        }

        DbRenderInfo dbRenderInfo = extractDbRenderInfo(flows);

        StringBuilder sb = new StringBuilder();
        sb.append("sequenceDiagram\n");

        EntryPointDto entry = sequenceInput.entry();
        if (entry != null) {
            String title = formatTitle(entry);
            if (!title.isEmpty()) {
                sb.append("title ").append(escapeMermaidLabel(title)).append("\n");
            }
        }

        // participant / actor 宣言（doc/sequenceDiagram.md の順序に合わせる）
        for (String originalId : participantOrder) {
            if (originalId == null) {
                continue;
            }
            ParticipantDto p = participantById.get(originalId);
            if (p == null) {
                continue;
            }
            String mermaidId = mermaidIdByOriginalId.getOrDefault(originalId, sanitizeParticipantId(originalId));
            String kind = p.kind() != null ? p.kind() : "";

            String nodeLabel;
            if ("DB".equals(p.id())) {
                nodeLabel = buildDbNodeLabel(dbRenderInfo);
            } else {
                nodeLabel = p.display();
            }

            String escapedLabel = escapeMermaidNodeLabel(nodeLabel);
            if ("actor".equalsIgnoreCase(kind)) {
                sb.append("actor ").append(mermaidId).append(" as ").append(escapedLabel).append("\n");
            } else {
                sb.append("participant ").append(mermaidId).append(" as ").append(escapedLabel).append("\n");
            }
        }

        // DB メタがある場合は、全メッセージより前に Note を出す（矢印の後だと図のレイアウトで見えにくいことがある）
        if (dbRenderInfo != null) {
            String dbMermaidId = mermaidIdByOriginalId.getOrDefault("DB", sanitizeParticipantId("DB"));
            sb.append("    Note right of ").append(dbMermaidId).append(": ")
                .append(escapeMermaidNodeLabel(buildDbNoteText(dbRenderInfo)))
                .append("\n");
        }

        // 動的フロー（呼び出しパス）と静的フロー（dependency）を区切る（P1）
        List<FlowDto> dynamicFlows = new ArrayList<>();
        List<FlowDto> dependencyOnlyFlows = new ArrayList<>();
        for (FlowDto f : flows) {
            if (f == null) {
                continue;
            }
            if ("dependency".equals(f.type())) {
                dependencyOnlyFlows.add(f);
            } else {
                dynamicFlows.add(f);
            }
        }
        String noteAnchor = resolveNoteAnchorMermaidId(participantOrder, mermaidIdByOriginalId);
        if (!dynamicFlows.isEmpty() && !dependencyOnlyFlows.isEmpty()) {
            sb.append("    Note right of ").append(noteAnchor).append(": ")
                .append(escapeMermaidNodeLabel("【動的フロー】"))
                .append("\n");
            renderFlows(sb, dynamicFlows, mermaidIdByOriginalId, 0);
            sb.append("    Note right of ").append(noteAnchor).append(": ")
                .append(escapeMermaidNodeLabel("【静的フロー】"))
                .append("\n");
            renderFlows(sb, dependencyOnlyFlows, mermaidIdByOriginalId, 0);
        } else {
            renderFlows(sb, flows, mermaidIdByOriginalId, 0);
        }

        return sb.toString().trim();
    }

    /**
     * 動的／静的の区切り Note に付けるアンカー participant の Mermaid ID（先頭の参加者を使用）。
     */
    private String resolveNoteAnchorMermaidId(
        List<String> participantOrder,
        Map<String, String> mermaidIdByOriginalId
    ) {
        if (participantOrder != null) {
            for (String originalId : participantOrder) {
                if (originalId != null && !originalId.isBlank()) {
                    return mermaidIdByOriginalId.getOrDefault(originalId, sanitizeParticipantId(originalId));
                }
            }
        }
        return mermaidIdByOriginalId.getOrDefault("User", sanitizeParticipantId("User"));
    }

    private String formatTitle(EntryPointDto entry) {
        String method = entry.httpMethod();
        String path = entry.path();
        String handler = entry.handler();

        StringBuilder sb = new StringBuilder();
        if (method != null && !method.isBlank()) {
            sb.append(method.trim());
        }
        if (path != null && !path.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(path.trim());
        }
        if (handler != null && !handler.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(" / ");
            }
            sb.append(handler.trim());
        }
        return sb.toString();
    }

    private void renderFlows(
        StringBuilder sb,
        List<FlowDto> flows,
        Map<String, String> mermaidIdByOriginalId,
        int indentLevel
    ) {
        if (flows == null || flows.isEmpty()) {
            return;
        }

        String indent = "    ".repeat(Math.max(0, indentLevel));
        for (FlowDto f : flows) {
            if (f == null || f.type() == null) {
                continue;
            }

            String type = f.type();
            switch (type) {
                case "call" -> renderCall(sb, indent, f, mermaidIdByOriginalId);
                case "return_view" -> renderReturnView(sb, indent, f, mermaidIdByOriginalId);
                case "session" -> renderSession(sb, indent, f, mermaidIdByOriginalId);
                case "db" -> renderDb(sb, indent, f, mermaidIdByOriginalId);
                case "dependency" -> renderDependency(sb, indent, f, mermaidIdByOriginalId);
                case "alt_hint" -> renderAltHint(sb, indent, f, mermaidIdByOriginalId, indentLevel);
                default -> {
                    // unknown type: ignore
                }
            }
        }
    }

    private void renderCall(StringBuilder sb, String indent, FlowDto f, Map<String, String> mermaidIdByOriginalId) {
        String from = mapParticipantId(f.from(), mermaidIdByOriginalId);
        String to = mapParticipantId(f.to(), mermaidIdByOriginalId);
        if (from.isEmpty() || to.isEmpty()) {
            return;
        }
        String label = f.label();
        String messageLabel = label != null ? escapeMermaidLabel(label) : "";
        sb.append(indent).append(from).append("->>").append(to);
        if (!messageLabel.isEmpty()) {
            sb.append(": ").append(messageLabel);
        }
        sb.append("\n");
    }

    private void renderReturnView(StringBuilder sb, String indent, FlowDto f, Map<String, String> mermaidIdByOriginalId) {
        String from = mapParticipantId(f.from(), mermaidIdByOriginalId);
        String to = mapParticipantId(f.to(), mermaidIdByOriginalId);
        if (from.isEmpty() || to.isEmpty()) {
            return;
        }
        String template = f.template();
        String messageLabel = template != null ? escapeMermaidLabel(template) : "return";
        sb.append(indent).append(from).append("->>").append(to).append(": ").append(messageLabel).append("\n");
    }

    private void renderSession(StringBuilder sb, String indent, FlowDto f, Map<String, String> mermaidIdByOriginalId) {
        String from = mapParticipantId(f.from(), mermaidIdByOriginalId);
        String to = mapParticipantId(f.to(), mermaidIdByOriginalId);
        if (from.isEmpty() || to.isEmpty()) {
            return;
        }
        String action = f.action() != null ? f.action() : "";
        String key = f.key();

        String messageLabel;
        if (!action.isEmpty() && key != null && !key.isBlank()) {
            messageLabel = action + "(" + key + ")";
        } else if (!action.isEmpty()) {
            messageLabel = action;
        } else if (key != null && !key.isBlank()) {
            messageLabel = "session(" + key + ")";
        } else {
            messageLabel = "session";
        }

        sb.append(indent).append(from).append("->>").append(to).append(": ").append(escapeMermaidLabel(messageLabel)).append("\n");
    }

    private void renderDb(
        StringBuilder sb,
        String indent,
        FlowDto f,
        Map<String, String> mermaidIdByOriginalId
    ) {
        String from = mapParticipantId(f.from(), mermaidIdByOriginalId);
        String to = mapParticipantId(f.to(), mermaidIdByOriginalId);
        if (from.isEmpty() || to.isEmpty()) {
            return;
        }
        String op = f.op() != null ? f.op() : "";
        String query = f.query();

        String messageLabel = formatDbArrowLabel(op, query);

        sb.append(indent).append(from).append("->>").append(to).append(": ")
            .append(escapeMermaidDbMessage(messageLabel)).append("\n");
    }

    /**
     * 依存関係一覧由来の静的依存（ラベルは依存種類コードのみ）。
     */
    private void renderDependency(
        StringBuilder sb,
        String indent,
        FlowDto f,
        Map<String, String> mermaidIdByOriginalId
    ) {
        String from = mapParticipantId(f.from(), mermaidIdByOriginalId);
        String to = mapParticipantId(f.to(), mermaidIdByOriginalId);
        if (from.isEmpty() || to.isEmpty()) {
            return;
        }
        // 依存ラベルには <br/> を含める（複数種類の改行）。escapeMermaidLabel は <br/> を除去するため使わない。
        String code = f.label() != null ? escapeMermaidNodeLabel(f.label()) : "";
        sb.append(indent).append(from).append("->>").append(to);
        if (!code.isEmpty()) {
            sb.append(": ").append(code);
        }
        sb.append("\n");
    }

    private void renderAltHint(
        StringBuilder sb,
        String indent,
        FlowDto f,
        Map<String, String> mermaidIdByOriginalId,
        int indentLevel
    ) {
        String condition = f.conditionText();
        String conditionLabel = condition != null ? escapeMermaidLabel(condition) : "condition";

        sb.append(indent).append("alt ").append(conditionLabel).append("\n");
        List<FlowDto> thenSteps = f.thenSteps() != null ? f.thenSteps() : List.of();
        renderFlows(sb, thenSteps, mermaidIdByOriginalId, indentLevel + 1);

        sb.append(indent).append("else\n");
        List<FlowDto> elseSteps = f.elseSteps() != null ? f.elseSteps() : List.of();
        renderFlows(sb, elseSteps, mermaidIdByOriginalId, indentLevel + 1);

        sb.append(indent).append("end\n");
    }

    private String mapParticipantId(String originalId, Map<String, String> mermaidIdByOriginalId) {
        if (originalId == null || originalId.isEmpty()) {
            return "";
        }
        String mapped = mermaidIdByOriginalId.get(originalId);
        if (mapped != null) {
            return mapped;
        }
        return sanitizeParticipantId(originalId);
    }

    private String sanitizeParticipantId(String originalId) {
        if (originalId == null || originalId.isBlank()) {
            return "Unknown";
        }
        String escaped = originalId.replaceAll("[^a-zA-Z0-9_]", "_");
        escaped = escaped.replaceAll("_{2,}", "_");
        escaped = escaped.replaceAll("^_+", "");
        escaped = escaped.replaceAll("_+$", "");
        if (escaped.isBlank()) {
            return "Unknown";
        }
        char first = escaped.charAt(0);
        if (Character.isDigit(first)) {
            return "P_" + escaped;
        }
        return escaped;
    }

    private String escapeMermaidLabel(String value) {
        if (value == null) {
            return "";
        }
        // Mermaid のラベル内の制御文字を簡易サニタイズ
        String v = value;
        v = v.replace("<br/>", " ").replace("<br>", " ");
        v = v.replace("\r", " ").replace("\n", " ");
        v = v.replace("|", "\\|");
        v = v.replace("\"", "'");
        v = v.trim();
        return v;
    }

    private void collectDbFlows(List<FlowDto> flows, List<FlowDto> out) {
        if (flows == null || flows.isEmpty()) {
            return;
        }
        for (FlowDto f : flows) {
            if (f == null) {
                continue;
            }
            if ("db".equals(f.type())) {
                out.add(f);
            }
            collectDbFlows(f.thenSteps(), out);
            collectDbFlows(f.elseSteps(), out);
            collectDbFlows(f.steps(), out);
        }
    }

    private DbRenderInfo extractDbRenderInfo(List<FlowDto> flows) {
        List<FlowDto> dbFlows = new ArrayList<>();
        collectDbFlows(flows, dbFlows);
        if (dbFlows.isEmpty()) {
            return null;
        }
        DbRenderInfo best = null;
        int bestScore = -1;
        for (FlowDto f : dbFlows) {
            DbRenderInfo candidate = fromDbFlow(f);
            int score = scoreDbRenderInfo(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private DbRenderInfo fromDbFlow(FlowDto f) {
        DbNoteDto note = f != null ? f.note() : null;
        String product = note != null && note.dbType() != null && !note.dbType().isBlank()
            ? note.dbType()
            : "Relational DB";
        String schema = note != null ? note.schema() : null;
        String table = note != null ? note.tableLogical() : null;
        if (table == null || table.isBlank()) {
            String query = f != null ? f.query() : null;
            if (query != null && !query.isBlank()) {
                table = truncate(query.replaceAll("\\s+", " "), 40);
            }
        }
        String op = f != null && f.op() != null ? f.op() : "";
        return new DbRenderInfo(product, schema, table, op);
    }

    private int scoreDbRenderInfo(DbRenderInfo info) {
        if (info == null) {
            return 0;
        }
        int s = 0;
        if (info.tableLine() != null && !info.tableLine().isBlank() && !"-".equals(info.tableLine())) {
            s += 5;
        }
        if (info.schemaLine() != null && !info.schemaLine().isBlank() && !"-".equals(info.schemaLine())) {
            s += 2;
        }
        if (info.productLabel() != null && !info.productLabel().isBlank() && !"Relational DB".equals(info.productLabel())) {
            s += 2;
        }
        if (info.accessOp() != null && !info.accessOp().isBlank()) {
            s += 1;
        }
        return s;
    }

    private String buildDbNodeLabel(DbRenderInfo info) {
        if (info == null) {
            return "DB<br/>-<br/>-";
        }
        // participant DB: 記憶媒体（種類の一行目 + テーブル／論理名）
        return "DB<br/>" + safeLine(info.productLabel())
            + "<br/>" + safeLine(tableOrDash(info.tableLine()));
    }

    /**
     * doc/sequenceDiagram.md: データベース／種類／スキーマ／テーブル名（論理名）
     */
    private String buildDbNoteText(DbRenderInfo info) {
        if (info == null) {
            return "データベース<br/>-<br/>-<br/>-";
        }
        return "データベース<br/>" + safeLine(info.productLabel())
            + "<br/>" + safeLine(schemaOrDash(info.schemaLine()))
            + "<br/>" + safeLine(tableOrDash(info.tableLine()));
    }

    private String schemaOrDash(String schema) {
        if (schema == null || schema.isBlank()) {
            return "-";
        }
        return schema;
    }

    private String tableOrDash(String table) {
        if (table == null || table.isBlank()) {
            return "-";
        }
        return table;
    }

    private String formatDbArrowLabel(String op, String query) {
        if (query == null || query.isBlank()) {
            return op.isEmpty() ? "db" : op;
        }
        String q = query.trim().replaceAll("\\s+", " ");
        if (q.length() > 72) {
            int split = q.lastIndexOf(' ', 72);
            if (split < 20) {
                split = 72;
            }
            String first = q.substring(0, split).trim();
            String rest = q.substring(split).trim();
            if (rest.length() > 72) {
                rest = rest.substring(0, 69) + "...";
            }
            q = first + "<br/>" + rest;
        }
        if (op.isEmpty()) {
            return q;
        }
        return op + "<br/>" + q;
    }

    private String escapeMermaidDbMessage(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\r", "").replace("\n", "<br/>");
        v = v.replace("|", "\\|");
        v = v.replace("\"", "'");
        return v.trim();
    }

    private String escapeMermaidNodeLabel(String value) {
        if (value == null) {
            return "";
        }
        // node label は <br/> を保持する（表示仕様に合わせる）
        String v = value;
        v = v.replace("\r", "").replace("\n", "<br/>");
        v = v.replace("|", "\\|");
        v = v.replace("\"", "'");
        return v.trim();
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (v.length() <= maxLen) {
            return v;
        }
        return v.substring(0, maxLen) + "...";
    }

    private String safeLine(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value;
    }

    /**
     * @param productLabel DB 種類（例: H2 Database）
     * @param schemaLine スキーマ
     * @param tableLine テーブル名（論理名）またはクエリ要約
     * @param accessOp JPQL/SQL 等
     */
    private record DbRenderInfo(String productLabel, String schemaLine, String tableLine, String accessOp) {
    }
}

