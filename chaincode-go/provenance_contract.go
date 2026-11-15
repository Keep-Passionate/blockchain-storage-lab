// 文件功能：以不可覆盖的四层业务对象保存虚拟溯源事实，并在链码内复核关系、身份和质量门。
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/hyperledger/fabric-contract-api-go/v2/contractapi"
)

// ProvenanceContract 是新业务合约；旧 TraceContract 仍保留为历史教学入口。
type ProvenanceContract struct{ contractapi.Contract }

// RecordBase 强制标记虚拟数据，防止实验样本被当作真实认证数据。
type RecordBase struct {
	SchemaVersion int  `json:"schemaVersion"`
	Synthetic     bool `json:"synthetic"`
}

// PlotData 保存体量较小的地块快照；纠错须新增编号并由业务关联新快照。
type PlotData struct {
	RecordBase
	PlotID      string   `json:"plotId"`
	RegionCode  string   `json:"regionCode"`
	Crop        string   `json:"crop"`
	Variety     string   `json:"variety"`
	GrowthStage string   `json:"growthStage"`
	AltitudeM   *float64 `json:"altitudeM"`
	Environment string   `json:"environment"`
}

// BatchData 用“种植区-年份-六位序号”唯一标识一个批次，并引用一份固定地块快照。
type BatchData struct {
	RecordBase
	BatchID    string `json:"batchId"`
	RegionCode string `json:"regionCode"`
	Year       int    `json:"year"`
	Sequence   int    `json:"sequence"`
	PlotID     string `json:"plotId"`
	StartedAt  string `json:"startedAt"`
}

// OperationData 是通用生产操作，不包含防控算法或销售流程。
type OperationData struct {
	RecordBase
	OperationID   string `json:"operationId"`
	BatchID       string `json:"batchId"`
	PlotID        string `json:"plotId"`
	OperationType string `json:"operationType"`
	PerformedAt   string `json:"performedAt"`
	OperatorID    string `json:"operatorId"`
	Description   string `json:"description"`
	RuleVersion   string `json:"ruleVersion"`
}

// InspectionData 只锚定报告 CID、原文字节 SHA-256 和结论；报告字节留在 IPFS。
type InspectionData struct {
	RecordBase
	InspectionID string `json:"inspectionId"`
	BatchID      string `json:"batchId"`
	ReportCID    string `json:"reportCid"`
	ReportSHA256 string `json:"reportSha256"`
	Conclusion   string `json:"conclusion"`
	LabName      string `json:"labName"`
	TestedAt     string `json:"testedAt"`
	Summary      string `json:"summary"`
}

// ReviewData 留下专家判断；APPROVE 仅代表演示审核通过，不代表法定认证。
type ReviewData struct {
	RecordBase
	ReviewID     string `json:"reviewId"`
	BatchID      string `json:"batchId"`
	InspectionID string `json:"inspectionId"`
	Decision     string `json:"decision"`
	Comment      string `json:"comment"`
}

// AuditData 记录监管复核及证据摘要；引用采用 kind/id，链码逐项检查存在性和批次归属。
type AuditData struct {
	RecordBase
	AuditID        string   `json:"auditId"`
	BatchID        string   `json:"batchId"`
	RecordIDs      []string `json:"recordIds"`
	EvidenceCID    string   `json:"evidenceCid"`
	EvidenceSHA256 string   `json:"evidenceSha256"`
	RuleVersion    string   `json:"ruleVersion"`
	Conclusion     string   `json:"conclusion"`
	Comment        string   `json:"comment"`
}

// AlertData 保存事件触发的模拟通知回执；不会向真实用户发送消息。
type AlertData struct {
	RecordBase
	AlertID        string `json:"alertId"`
	BatchID        string `json:"batchId"`
	SourceKind     string `json:"sourceKind"`
	SourceID       string `json:"sourceId"`
	DeliveryStatus string `json:"deliveryStatus"`
	Recipient      string `json:"recipient"`
	Comment        string `json:"comment"`
}

// LedgerRecord 将业务字段与真实交易号、提交证书身份绑定，操作者声明不能替代身份授权。
type LedgerRecord struct {
	Kind         string          `json:"kind"`
	ID           string          `json:"id"`
	BatchID      string          `json:"batchId"`
	Data         json.RawMessage `json:"data"`
	TxID         string          `json:"txId"`
	SubmitterMSP string          `json:"submitterMsp"`
	SubmitterID  string          `json:"submitterId"`
}

var safeID = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,128}$`)
var regionID = regexp.MustCompile(`^[A-Z0-9]{2,12}$`)
var digestHex = regexp.MustCompile(`^[a-f0-9]{64}$`)
var contentID = regexp.MustCompile(`^[A-Za-z0-9]{20,128}$`)

// PutPlot 全量保存一份地块快照。
func (c *ProvenanceContract) PutPlot(ctx contractapi.TransactionContextInterface, payloadJSON string) (string, error) {
	var d PlotData
	if err := decodeData(payloadJSON, &d); err != nil {
		return "", err
	}
	if err := checkBase(d.RecordBase); err != nil {
		return "", err
	}
	if !regionID.MatchString(d.RegionCode) {
		return "", problem("INVALID_REGION", "regionCode")
	}
	if err := requireText(d.Crop, d.Variety, d.GrowthStage, d.Environment); err != nil {
		return "", err
	}
	if d.AltitudeM == nil || *d.AltitudeM < -500 || *d.AltitudeM > 9000 {
		return "", problem("INVALID_ALTITUDE", "altitudeM")
	}
	return c.store(ctx, "plot", d.PlotID, "", d, "Org1MSP", "RecordCommitted")
}

// PutBatch 保证全局批次号格式和地块归属，重复编号不能覆盖原记录。
func (c *ProvenanceContract) PutBatch(ctx contractapi.TransactionContextInterface, payloadJSON string) (string, error) {
	var d BatchData
	if err := decodeData(payloadJSON, &d); err != nil {
		return "", err
	}
	if err := checkBase(d.RecordBase); err != nil {
		return "", err
	}
	if !regionID.MatchString(d.RegionCode) || d.Year < 2000 || d.Year > 2100 || d.Sequence < 1 || d.Sequence > 999999 || d.BatchID != fmt.Sprintf("%s-%04d-%06d", d.RegionCode, d.Year, d.Sequence) {
		return "", problem("INVALID_BATCH_ID", "expected regionCode-year-sixDigitSequence")
	}
	started, err := parseTime(d.StartedAt)
	if err != nil {
		return "", err
	}
	if started.Year() != d.Year {
		return "", problem("YEAR_MISMATCH", "startedAt")
	}
	if err := checkNotFuture(ctx, started); err != nil {
		return "", err
	}
	plot, err := c.load(ctx, "plot", d.PlotID)
	if err != nil {
		return "", err
	}
	var pd PlotData
	if err := json.Unmarshal(plot.Data, &pd); err != nil {
		return "", err
	}
	if pd.RegionCode != d.RegionCode {
		return "", problem("REGION_MISMATCH", "plot and batch")
	}
	return c.store(ctx, "batch", d.BatchID, d.BatchID, d, "Org1MSP", "RecordCommitted")
}

// PutOperation 再次检查操作内容；绕过 Java 校验也不能把异常操作写入正常账本。
func (c *ProvenanceContract) PutOperation(ctx contractapi.TransactionContextInterface, payloadJSON string) (string, error) {
	d, err := c.checkOperation(ctx, payloadJSON)
	if err != nil {
		return "", err
	}
	return c.store(ctx, "operation", d.OperationID, d.BatchID, d, "Org1MSP", "RecordCommitted")
}

// CheckOperation 只读预校验；真正写入仍复用全部校验，拒绝信息不会被写进正常操作账本。
func (c *ProvenanceContract) CheckOperation(ctx contractapi.TransactionContextInterface, payloadJSON string) (string, error) {
	_, err := c.checkOperation(ctx, payloadJSON)
	result := struct {
		Valid       bool     `json:"valid"`
		ReasonCodes []string `json:"reasonCodes"`
	}{Valid: err == nil, ReasonCodes: []string{}}
	if err != nil {
		var ruleErr *RuleError
		if !errors.As(err, &ruleErr) || ruleErr.Code == "FORBIDDEN" {
			return "", err
		}
		result.ReasonCodes = append(result.ReasonCodes, ruleErr.Code)
	}
	payload, marshalErr := json.Marshal(result)
	return string(payload), marshalErr
}

// checkOperation 返回有类型的规则错误，读状态或身份服务失败原样上抛而不伪装成业务拒绝。
func (c *ProvenanceContract) checkOperation(ctx contractapi.TransactionContextInterface, payloadJSON string) (OperationData, error) {
	var d OperationData
	msp, err := ctx.GetClientIdentity().GetMSPID()
	if err != nil {
		return d, err
	}
	if msp != "Org1MSP" {
		return d, problem("FORBIDDEN", "Org1MSP required")
	}
	if err := decodeData(payloadJSON, &d); err != nil {
		return d, err
	}
	if err := checkBase(d.RecordBase); err != nil {
		return d, err
	}
	if err := validateOperationFields(d); err != nil {
		return d, err
	}
	batch, err := c.batch(ctx, d.BatchID)
	if err != nil {
		return d, err
	}
	if d.PlotID != batch.PlotID {
		return d, problem("PLOT_MISMATCH", "operation and batch")
	}
	if err := checkBusinessTime(ctx, d.PerformedAt, batch.StartedAt); err != nil {
		return d, err
	}
	existing, err := ctx.GetStub().GetState(recordKey("operation", d.OperationID))
	if err != nil {
		return d, err
	}
	if len(existing) != 0 {
		return d, problem("RECORD_EXISTS", "operation/"+d.OperationID)
	}
	return d, nil
}

// PutInspection 上链存证检测摘要，FAIL 也上链并发出异常事件；报告原文不上链。
func (c *ProvenanceContract) PutInspection(ctx contractapi.TransactionContextInterface, payloadJSON string) (string, error) {
	var d InspectionData
	if err := decodeData(payloadJSON, &d); err != nil {
		return "", err
	}
	if err := checkBase(d.RecordBase); err != nil {
		return "", err
	}
	if !contentID.MatchString(d.ReportCID) {
		return "", problem("INVALID_CID", "reportCid")
	}
	if !digestHex.MatchString(d.ReportSHA256) {
		return "", problem("INVALID_HASH", "reportSha256")
	}
	if d.Conclusion != "PASS" && d.Conclusion != "FAIL" {
		return "", problem("INVALID_CONCLUSION", "inspection")
	}
	if err := requireText(d.LabName, d.Summary); err != nil {
		return "", err
	}
	batch, err := c.batch(ctx, d.BatchID)
	if err != nil {
		return "", err
	}
	if err := checkBusinessTime(ctx, d.TestedAt, batch.StartedAt); err != nil {
		return "", err
	}
	event := "RecordCommitted"
	if d.Conclusion == "FAIL" {
		event = "InspectionFailed"
	}
	return c.store(ctx, "inspection", d.InspectionID, d.BatchID, d, "Org1MSP", event)
}

// PutReview 限制专家组织提交，且不能把不合格检测直接审核为通过。
func (c *ProvenanceContract) PutReview(ctx contractapi.TransactionContextInterface, payloadJSON string) (string, error) {
	var d ReviewData
	if err := decodeData(payloadJSON, &d); err != nil {
		return "", err
	}
	if err := checkBase(d.RecordBase); err != nil {
		return "", err
	}
	if d.Decision != "APPROVE" && d.Decision != "REJECT" {
		return "", problem("INVALID_DECISION", "review")
	}
	if err := requireText(d.Comment); err != nil {
		return "", err
	}
	inspection, err := c.load(ctx, "inspection", d.InspectionID)
	if err != nil {
		return "", err
	}
	if inspection.BatchID != d.BatchID {
		return "", problem("BATCH_MISMATCH", "review and inspection")
	}
	var result InspectionData
	if err := json.Unmarshal(inspection.Data, &result); err != nil {
		return "", err
	}
	if result.Conclusion == "FAIL" && d.Decision == "APPROVE" {
		return "", problem("QUALITY_GATE_FAILED", "failed inspection cannot be approved")
	}
	return c.store(ctx, "review", d.ReviewID, d.BatchID, d, "Org2MSP", "RecordCommitted")
}

// PutAudit 保存批量复核结论；摘要由链下审计服务计算，链码检查它的格式和引用边界。
func (c *ProvenanceContract) PutAudit(ctx contractapi.TransactionContextInterface, payloadJSON string) (string, error) {
	var d AuditData
	if err := decodeData(payloadJSON, &d); err != nil {
		return "", err
	}
	if err := checkBase(d.RecordBase); err != nil {
		return "", err
	}
	if !contentID.MatchString(d.EvidenceCID) {
		return "", problem("INVALID_CID", "evidenceCid")
	}
	if !digestHex.MatchString(d.EvidenceSHA256) {
		return "", problem("INVALID_HASH", "evidenceSha256")
	}
	if d.RuleVersion != "trace-rules-v1" {
		return "", problem("RULE_VERSION", "trace-rules-v1 required")
	}
	if d.Conclusion != "CLEAR" && d.Conclusion != "FLAGGED" {
		return "", problem("INVALID_CONCLUSION", "audit")
	}
	if err := requireText(d.Comment); err != nil {
		return "", err
	}
	if _, err := c.batch(ctx, d.BatchID); err != nil {
		return "", err
	}
	if len(d.RecordIDs) == 0 || len(d.RecordIDs) > 100 {
		return "", problem("INVALID_AUDIT_REFERENCES", "require 1..100 records")
	}
	seen := make(map[string]bool)
	for _, ref := range d.RecordIDs {
		parts := strings.Split(ref, "/")
		if len(parts) != 2 || (parts[0] != "operation" && parts[0] != "inspection" && parts[0] != "review" && parts[0] != "batch") || seen[ref] {
			return "", problem("INVALID_AUDIT_REFERENCES", ref)
		}
		seen[ref] = true
		record, err := c.load(ctx, parts[0], parts[1])
		if err != nil {
			return "", err
		}
		if record.BatchID != d.BatchID {
			return "", problem("BATCH_MISMATCH", "audit reference")
		}
	}
	event := "RecordCommitted"
	if d.Conclusion == "FLAGGED" {
		event = "AuditFlagged"
	}
	return c.store(ctx, "audit", d.AuditID, d.BatchID, d, "Org2MSP", event)
}

// PutAlert 只接受已上链异常作为来源，防止制造无来源的预警日志。
func (c *ProvenanceContract) PutAlert(ctx contractapi.TransactionContextInterface, payloadJSON string) (string, error) {
	var d AlertData
	if err := decodeData(payloadJSON, &d); err != nil {
		return "", err
	}
	if err := checkBase(d.RecordBase); err != nil {
		return "", err
	}
	if d.SourceKind != "inspection" && d.SourceKind != "audit" {
		return "", problem("INVALID_ALERT_SOURCE", "sourceKind")
	}
	if d.DeliveryStatus != "SIMULATED" {
		return "", problem("INVALID_DELIVERY_STATUS", "only simulated delivery is implemented")
	}
	if err := requireText(d.Recipient, d.Comment); err != nil {
		return "", err
	}
	source, err := c.load(ctx, d.SourceKind, d.SourceID)
	if err != nil {
		return "", err
	}
	if source.BatchID != d.BatchID {
		return "", problem("BATCH_MISMATCH", "alert source")
	}
	var conclusion struct {
		Conclusion string `json:"conclusion"`
	}
	if err := json.Unmarshal(source.Data, &conclusion); err != nil {
		return "", err
	}
	if (d.SourceKind == "inspection" && conclusion.Conclusion != "FAIL") || (d.SourceKind == "audit" && conclusion.Conclusion != "FLAGGED") {
		return "", problem("INVALID_ALERT_SOURCE", "source is not flagged")
	}
	return c.store(ctx, "alert", d.AlertID, d.BatchID, d, "Org2MSP", "RecordCommitted")
}

// ReadRecord 读取可信链上对象；业务数据库只能保存这一对象的查询副本。
func (c *ProvenanceContract) ReadRecord(ctx contractapi.TransactionContextInterface, kind, id string) (string, error) {
	record, err := c.load(ctx, kind, id)
	if err != nil {
		return "", err
	}
	payload, err := json.Marshal(record)
	return string(payload), err
}

// ListBatch 返回地块快照及该批次的所有关联记录，使用组合键而非数据库专属查询语法。
func (c *ProvenanceContract) ListBatch(ctx contractapi.TransactionContextInterface, batchID string) (string, error) {
	batch, err := c.batch(ctx, batchID)
	if err != nil {
		return "", err
	}
	plot, err := c.load(ctx, "plot", batch.PlotID)
	if err != nil {
		return "", err
	}
	iter, err := ctx.GetStub().GetStateByPartialCompositeKey("provenanceBatch", []string{batchID})
	if err != nil {
		return "", err
	}
	defer iter.Close()
	records := []LedgerRecord{plot}
	for iter.HasNext() {
		if len(records) >= 1000 {
			return "", problem("QUERY_LIMIT", "batch exceeds 999 records; add pagination before scaling")
		}
		entry, err := iter.Next()
		if err != nil {
			return "", err
		}
		_, attrs, err := ctx.GetStub().SplitCompositeKey(entry.Key)
		if err != nil {
			return "", err
		}
		if len(attrs) != 3 {
			return "", problem("INVALID_INDEX", "provenanceBatch")
		}
		record, err := c.load(ctx, attrs[1], attrs[2])
		if err != nil {
			return "", err
		}
		records = append(records, record)
	}
	// 组合键的字典序不是业务依赖序；让数据库重建能先写父对象，再写引用对象。
	rank := map[string]int{"plot": 0, "batch": 1, "operation": 2, "inspection": 3, "review": 4, "audit": 5, "alert": 6}
	sort.Slice(records, func(i, j int) bool {
		if rank[records[i].Kind] != rank[records[j].Kind] {
			return rank[records[i].Kind] < rank[records[j].Kind]
		}
		return records[i].ID < records[j].ID
	})
	payload, err := json.Marshal(records)
	return string(payload), err
}

// store 统一执行真实身份授权、不可覆盖写入、批次索引和合约事件。
func (c *ProvenanceContract) store(ctx contractapi.TransactionContextInterface, kind, id, batchID string, data interface{}, allowedMSP, event string) (string, error) {
	if !safeID.MatchString(id) {
		return "", problem("INVALID_ID", kind)
	}
	if batchID != "" && !safeID.MatchString(batchID) {
		return "", problem("INVALID_ID", "batchId")
	}
	msp, err := ctx.GetClientIdentity().GetMSPID()
	if err != nil {
		return "", err
	}
	if msp != allowedMSP {
		return "", problem("FORBIDDEN", allowedMSP+" required")
	}
	identity, err := ctx.GetClientIdentity().GetID()
	if err != nil {
		return "", err
	}
	key := recordKey(kind, id)
	existing, err := ctx.GetStub().GetState(key)
	if err != nil {
		return "", err
	}
	if len(existing) != 0 {
		return "", problem("RECORD_EXISTS", kind+"/"+id)
	}
	raw, err := json.Marshal(data)
	if err != nil {
		return "", err
	}
	record := LedgerRecord{kind, id, batchID, raw, ctx.GetStub().GetTxID(), msp, identity}
	payload, err := json.Marshal(record)
	if err != nil {
		return "", err
	}
	if err := ctx.GetStub().PutState(key, payload); err != nil {
		return "", err
	}
	if batchID != "" {
		index, err := ctx.GetStub().CreateCompositeKey("provenanceBatch", []string{batchID, kind, id})
		if err != nil {
			return "", err
		}
		if err := ctx.GetStub().PutState(index, []byte{0}); err != nil {
			return "", err
		}
	}
	if err := ctx.GetStub().SetEvent(event, payload); err != nil {
		return "", err
	}
	return string(payload), nil
}

func (c *ProvenanceContract) load(ctx contractapi.TransactionContextInterface, kind, id string) (LedgerRecord, error) {
	var record LedgerRecord
	switch kind {
	case "plot", "batch", "operation", "inspection", "review", "audit", "alert":
	default:
		return record, problem("INVALID_KIND", kind)
	}
	if !safeID.MatchString(id) {
		return record, problem("INVALID_ID", kind)
	}
	payload, err := ctx.GetStub().GetState(recordKey(kind, id))
	if err != nil {
		return record, err
	}
	if len(payload) == 0 {
		return record, problem("NOT_FOUND", kind+"/"+id)
	}
	err = json.Unmarshal(payload, &record)
	return record, err
}

func (c *ProvenanceContract) batch(ctx contractapi.TransactionContextInterface, id string) (BatchData, error) {
	var batch BatchData
	record, err := c.load(ctx, "batch", id)
	if err != nil {
		return batch, err
	}
	err = json.Unmarshal(record.Data, &batch)
	return batch, err
}

// decodeData 拒绝未知字段，避免误把文件原文、密码或旧版 DTO 静默写进账本。
func decodeData(payload string, dst interface{}) error {
	if len(payload) == 0 || len(payload) > 32768 {
		return problem("INVALID_JSON", "payload size must be 1..32768 bytes")
	}
	decoder := json.NewDecoder(bytes.NewBufferString(payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(dst); err != nil {
		return problem("INVALID_JSON", err.Error())
	}
	if err := decoder.Decode(new(interface{})); err != io.EOF {
		return problem("INVALID_JSON", "exactly one JSON object required")
	}
	return nil
}

func checkBase(base RecordBase) error {
	if base.SchemaVersion != 1 {
		return problem("SCHEMA_VERSION", "only version 1 is supported")
	}
	if !base.Synthetic {
		return problem("SYNTHETIC_REQUIRED", "this experimental deployment only accepts synthetic data")
	}
	return nil
}

func validateOperationFields(d OperationData) error {
	if !safeID.MatchString(d.OperationID) || !safeID.MatchString(d.PlotID) || !safeID.MatchString(d.OperatorID) {
		return problem("INVALID_ID", "operationId/plotId/operatorId")
	}
	switch d.OperationType {
	case "PLANTING", "IRRIGATION", "HARVEST", "PROCESSING", "STORAGE", "OBSERVATION":
	default:
		return problem("OPERATION_TYPE_NOT_ALLOWED", d.OperationType)
	}
	if d.RuleVersion != "trace-rules-v1" {
		return problem("RULE_VERSION", "trace-rules-v1 required")
	}
	return requireText(d.Description)
}

func requireText(values ...string) error {
	for _, value := range values {
		if strings.TrimSpace(value) == "" || len(value) > 4096 {
			return problem("INVALID_TEXT", "required text must be 1..4096 bytes")
		}
	}
	return nil
}

func parseTime(value string) (time.Time, error) {
	if len(value) > 40 {
		return time.Time{}, problem("INVALID_TIME", "RFC3339 timestamp exceeds 40 bytes")
	}
	t, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return t, problem("INVALID_TIME", "RFC3339 required")
	}
	return t, nil
}

func checkNotFuture(ctx contractapi.TransactionContextInterface, value time.Time) error {
	timestamp, err := ctx.GetStub().GetTxTimestamp()
	if err != nil {
		return err
	}
	if timestamp == nil {
		return fmt.Errorf("missing Fabric transaction timestamp")
	}
	if err := timestamp.CheckValid(); err != nil {
		return fmt.Errorf("invalid Fabric transaction timestamp: %w", err)
	}
	if value.After(timestamp.AsTime()) {
		return problem("FUTURE_TIME", "business time exceeds transaction timestamp")
	}
	return nil
}

func checkBusinessTime(ctx contractapi.TransactionContextInterface, value, start string) error {
	current, err := parseTime(value)
	if err != nil {
		return err
	}
	baseline, err := parseTime(start)
	if err != nil {
		return err
	}
	if current.Before(baseline) {
		return problem("BEFORE_BATCH_START", "business time")
	}
	return checkNotFuture(ctx, current)
}

func recordKey(kind, id string) string { return "provenance:" + kind + ":" + id }

// RuleError 只表示确定性的业务拒绝，不表示网络超时或存储故障。
type RuleError struct {
	Code   string
	Detail string
}

func (e *RuleError) Error() string      { return e.Code + ": " + e.Detail }
func problem(code, detail string) error { return &RuleError{Code: code, Detail: detail} }
