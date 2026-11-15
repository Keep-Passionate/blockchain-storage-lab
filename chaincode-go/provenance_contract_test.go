// 文件功能：用最小 Fabric stub 验证业务拒绝、身份授权、跨对象关系、预警来源和幂等边界。
package main

import (
	"encoding/json"
	"errors"
	"io"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/hyperledger/fabric-chaincode-go/v2/pkg/cid"
	"github.com/hyperledger/fabric-chaincode-go/v2/shim"
	"github.com/hyperledger/fabric-contract-api-go/v2/contractapi"
	"github.com/hyperledger/fabric-protos-go-apiv2/ledger/queryresult"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// memoryStub 仅实现当前合约实际使用的链码接口，不启动 Docker 或伪造集成测试成功。
type memoryStub struct {
	shim.ChaincodeStubInterface
	state   map[string][]byte
	events  []string
	readErr error
}

func (s *memoryStub) GetState(key string) ([]byte, error) {
	if s.readErr != nil {
		return nil, s.readErr
	}
	return s.state[key], nil
}
func (s *memoryStub) PutState(key string, value []byte) error {
	s.state[key] = append([]byte(nil), value...)
	return nil
}
func (s *memoryStub) GetTxID() string { return "unit-test-transaction" }
func (s *memoryStub) GetTxTimestamp() (*timestamppb.Timestamp, error) {
	t, _ := time.Parse(time.RFC3339, "2026-11-15T00:00:00Z")
	return timestamppb.New(t), nil
}
func (s *memoryStub) SetEvent(name string, payload []byte) error {
	s.events = append(s.events, name)
	return nil
}
func (s *memoryStub) CreateCompositeKey(kind string, attrs []string) (string, error) {
	return "\x00" + kind + "\x00" + strings.Join(attrs, "\x00") + "\x00", nil
}
func (s *memoryStub) SplitCompositeKey(key string) (string, []string, error) {
	parts := strings.Split(key, "\x00")
	return parts[1], parts[2 : len(parts)-1], nil
}
func (s *memoryStub) GetStateByPartialCompositeKey(kind string, attrs []string) (shim.StateQueryIteratorInterface, error) {
	prefix, _ := s.CreateCompositeKey(kind, attrs)
	keys := []string{}
	for key := range s.state {
		if strings.HasPrefix(key, prefix) {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	return &memoryIterator{keys: keys, state: s.state}, nil
}

type memoryIterator struct {
	keys  []string
	state map[string][]byte
	pos   int
}

func (i *memoryIterator) HasNext() bool { return i.pos < len(i.keys) }
func (i *memoryIterator) Close() error  { return nil }
func (i *memoryIterator) Next() (*queryresult.KV, error) {
	if !i.HasNext() {
		return nil, io.EOF
	}
	key := i.keys[i.pos]
	i.pos++
	return &queryresult.KV{Key: key, Value: i.state[key]}, nil
}

// testIdentity 用组织和证书主体测试授权，不能由业务里的 operatorId 替代。
type testIdentity struct {
	cid.ClientIdentity
	msp string
}

func (i *testIdentity) GetMSPID() (string, error) { return i.msp, nil }
func (i *testIdentity) GetID() (string, error)    { return "x509::test-user::test-ca", nil }

func setupLedger(t *testing.T) (*ProvenanceContract, *contractapi.TransactionContext, *memoryStub) {
	t.Helper()
	s := &memoryStub{state: make(map[string][]byte)}
	ctx := new(contractapi.TransactionContext)
	ctx.SetStub(s)
	ctx.SetClientIdentity(&testIdentity{msp: "Org1MSP"})
	c := new(ProvenanceContract)
	if _, err := c.PutPlot(ctx, plotJSON()); err != nil {
		t.Fatal(err)
	}
	if _, err := c.PutBatch(ctx, batchJSON("TS01-2026-000001", 1)); err != nil {
		t.Fatal(err)
	}
	return c, ctx, s
}

func plotJSON() string {
	return `{"schemaVersion":1,"synthetic":true,"plotId":"PLOT-01","regionCode":"TS01","crop":"tea","variety":"synthetic-variety","growthStage":"growth","altitudeM":150.5,"environment":"synthetic terrain"}`
}
func batchJSON(id string, sequence int) string {
	payload, _ := json.Marshal(BatchData{RecordBase: RecordBase{1, true}, BatchID: id, RegionCode: "TS01", Year: 2026, Sequence: sequence, PlotID: "PLOT-01", StartedAt: "2026-11-01T00:00:00Z"})
	return string(payload)
}
func operationData() OperationData {
	return OperationData{RecordBase: RecordBase{1, true}, OperationID: "OP-01", BatchID: "TS01-2026-000001", PlotID: "PLOT-01", OperationType: "IRRIGATION", PerformedAt: "2026-11-10T00:00:00Z", OperatorID: "farmer-01", Description: "synthetic irrigation", RuleVersion: "trace-rules-v1"}
}
func inspectionData(id, batchID, conclusion string) InspectionData {
	return InspectionData{RecordBase: RecordBase{1, true}, InspectionID: id, BatchID: batchID, ReportCID: "QmSyntheticReportForUnitTest1234567890", ReportSHA256: strings.Repeat("a", 64), Conclusion: conclusion, LabName: "synthetic-lab", TestedAt: "2026-11-10T00:00:00Z", Summary: "synthetic quality result"}
}
func encodeTest(t *testing.T, value interface{}) string {
	t.Helper()
	b, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}
func assertCode(t *testing.T, err error, code string) {
	t.Helper()
	var rule *RuleError
	if !errors.As(err, &rule) || rule.Code != code {
		t.Fatalf("want %s, got %v", code, err)
	}
}

func TestFourLayersRoundTripAndImmutableIdentity(t *testing.T) {
	c, ctx, stub := setupLedger(t)
	operation := operationData()
	before := len(stub.state)
	check, err := c.CheckOperation(ctx, encodeTest(t, operation))
	if err != nil || check != `{"valid":true,"reasonCodes":[]}` {
		t.Fatalf("check=%s err=%v", check, err)
	}
	if len(stub.state) != before {
		t.Fatal("read-only check changed state")
	}
	result, err := c.PutOperation(ctx, encodeTest(t, operation))
	if err != nil {
		t.Fatal(err)
	}
	var record LedgerRecord
	if err := json.Unmarshal([]byte(result), &record); err != nil {
		t.Fatal(err)
	}
	if record.SubmitterMSP != "Org1MSP" || record.SubmitterID != "x509::test-user::test-ca" || record.TxID != "unit-test-transaction" {
		t.Fatalf("missing real submitter binding: %+v", record)
	}
	if _, err := c.PutInspection(ctx, encodeTest(t, inspectionData("INSP-01", operation.BatchID, "PASS"))); err != nil {
		t.Fatal(err)
	}
	all, err := c.ListBatch(ctx, operation.BatchID)
	if err != nil {
		t.Fatal(err)
	}
	var records []LedgerRecord
	if err := json.Unmarshal([]byte(all), &records); err != nil {
		t.Fatal(err)
	}
	if len(records) != 4 {
		t.Fatalf("expected plot+batch+operation+inspection, got %d", len(records))
	}
	for i, kind := range []string{"plot", "batch", "operation", "inspection"} {
		if records[i].Kind != kind {
			t.Fatalf("projection dependency order: index %d want %s got %s", i, kind, records[i].Kind)
		}
	}
	before = len(stub.state)
	_, err = c.PutOperation(ctx, encodeTest(t, operation))
	assertCode(t, err, "RECORD_EXISTS")
	if len(stub.state) != before {
		t.Fatal("duplicate mutated immutable record")
	}
	check, err = c.CheckOperation(ctx, encodeTest(t, operation))
	if err != nil || !strings.Contains(check, "RECORD_EXISTS") {
		t.Fatalf("check=%s err=%v", check, err)
	}
}

func TestOperationRejectionsAlsoApplyToDirectSubmission(t *testing.T) {
	tests := []struct {
		name, code string
		mutate     func(*OperationData)
	}{
		{"wrong-plot", "PLOT_MISMATCH", func(d *OperationData) { d.PlotID = "OTHER-PLOT" }},
		{"before-start", "BEFORE_BATCH_START", func(d *OperationData) { d.PerformedAt = "2026-10-31T00:00:00Z" }},
		{"future", "FUTURE_TIME", func(d *OperationData) { d.PerformedAt = "2026-12-01T00:00:00Z" }},
		{"invalid-time", "INVALID_TIME", func(d *OperationData) { d.PerformedAt = "yesterday" }},
		{"sales-out-of-scope", "OPERATION_TYPE_NOT_ALLOWED", func(d *OperationData) { d.OperationType = "SALES" }},
		{"missing-description", "INVALID_TEXT", func(d *OperationData) { d.Description = " " }},
		{"bad-rule", "RULE_VERSION", func(d *OperationData) { d.RuleVersion = "bypass" }},
		{"not-synthetic", "SYNTHETIC_REQUIRED", func(d *OperationData) { d.Synthetic = false }},
		{"unsupported-schema", "SCHEMA_VERSION", func(d *OperationData) { d.SchemaVersion = 2 }},
		{"missing-batch", "NOT_FOUND", func(d *OperationData) { d.BatchID = "TS01-2026-999999" }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			c, ctx, stub := setupLedger(t)
			d := operationData()
			test.mutate(&d)
			before := len(stub.state)
			check, err := c.CheckOperation(ctx, encodeTest(t, d))
			if err != nil || !strings.Contains(check, test.code) {
				t.Fatalf("check=%s err=%v", check, err)
			}
			_, err = c.PutOperation(ctx, encodeTest(t, d))
			assertCode(t, err, test.code)
			if len(stub.state) != before {
				t.Fatal("rejection persisted normal record")
			}
		})
	}
}

func TestInternalFailureIsNotBusinessRejection(t *testing.T) {
	c, ctx, stub := setupLedger(t)
	stub.readErr = errors.New("state database unavailable")
	result, err := c.CheckOperation(ctx, encodeTest(t, operationData()))
	if err == nil || result != "" || err.Error() != "state database unavailable" {
		t.Fatalf("result=%s err=%v", result, err)
	}
}

func TestBatchNumberRegionAndUnknownFieldValidation(t *testing.T) {
	c, ctx, stub := setupLedger(t)
	before := len(stub.state)
	_, err := c.PutBatch(ctx, batchJSON("TS01-2026-000001", 2))
	assertCode(t, err, "INVALID_BATCH_ID")
	_, err = c.PutBatch(ctx, strings.ReplaceAll(batchJSON("TS01-2026-000002", 2), "TS01", "TS02"))
	assertCode(t, err, "REGION_MISMATCH")
	_, err = c.PutPlot(ctx, strings.Replace(plotJSON(), `"plotId"`, `"reportBytes":"must not be on chain","plotId"`, 1))
	assertCode(t, err, "INVALID_JSON")
	_, err = c.PutPlot(ctx, plotJSON()+plotJSON())
	assertCode(t, err, "INVALID_JSON")
	_, err = c.ReadRecord(ctx, "batch", "not-there")
	assertCode(t, err, "NOT_FOUND")
	if len(stub.state) != before {
		t.Fatal("invalid input mutated state")
	}
}

func TestExpertReviewAuthorizationAndQualityGate(t *testing.T) {
	c, ctx, stub := setupLedger(t)
	if _, err := c.PutInspection(ctx, encodeTest(t, inspectionData("INSP-FAIL", "TS01-2026-000001", "FAIL"))); err != nil {
		t.Fatal(err)
	}
	if stub.events[len(stub.events)-1] != "InspectionFailed" {
		t.Fatal("missing failed-inspection event")
	}
	review := ReviewData{RecordBase: RecordBase{1, true}, ReviewID: "REV-01", BatchID: "TS01-2026-000001", InspectionID: "INSP-FAIL", Decision: "REJECT", Comment: "synthetic expert rejection"}
	_, err := c.PutReview(ctx, encodeTest(t, review))
	assertCode(t, err, "FORBIDDEN")
	ctx.SetClientIdentity(&testIdentity{msp: "Org2MSP"})
	review.Decision = "APPROVE"
	_, err = c.PutReview(ctx, encodeTest(t, review))
	assertCode(t, err, "QUALITY_GATE_FAILED")
	review.Decision = "REJECT"
	review.BatchID = "TS01-2026-000002"
	_, err = c.PutReview(ctx, encodeTest(t, review))
	assertCode(t, err, "BATCH_MISMATCH")
	review.BatchID = "TS01-2026-000001"
	if _, err = c.PutReview(ctx, encodeTest(t, review)); err != nil {
		t.Fatal(err)
	}
	_, err = c.PutOperation(ctx, encodeTest(t, operationData()))
	assertCode(t, err, "FORBIDDEN")
	_, err = c.CheckOperation(ctx, encodeTest(t, operationData()))
	assertCode(t, err, "FORBIDDEN")
}

func TestAuditReferencesAndAlertSource(t *testing.T) {
	c, ctx, stub := setupLedger(t)
	if _, err := c.PutInspection(ctx, encodeTest(t, inspectionData("INSP-PASS", "TS01-2026-000001", "PASS"))); err != nil {
		t.Fatal(err)
	}
	if _, err := c.PutBatch(ctx, batchJSON("TS01-2026-000002", 2)); err != nil {
		t.Fatal(err)
	}
	ctx.SetClientIdentity(&testIdentity{msp: "Org2MSP"})
	audit := AuditData{RecordBase: RecordBase{1, true}, AuditID: "AUDIT-01", BatchID: "TS01-2026-000001", RecordIDs: []string{"inspection/INSP-PASS"}, EvidenceCID: "QmSyntheticAuditForUnitTest1234567890", EvidenceSHA256: strings.Repeat("b", 64), RuleVersion: "trace-rules-v1", Conclusion: "FLAGGED", Comment: "synthetic rejected-input audit"}
	audit.RecordIDs = []string{"batch/TS01-2026-000002"}
	_, err := c.PutAudit(ctx, encodeTest(t, audit))
	assertCode(t, err, "BATCH_MISMATCH")
	audit.RecordIDs = []string{"inspection/absent"}
	_, err = c.PutAudit(ctx, encodeTest(t, audit))
	assertCode(t, err, "NOT_FOUND")
	audit.RecordIDs = []string{"inspection/INSP-PASS"}
	if _, err = c.PutAudit(ctx, encodeTest(t, audit)); err != nil {
		t.Fatal(err)
	}
	if stub.events[len(stub.events)-1] != "AuditFlagged" {
		t.Fatal("missing audit event")
	}
	alert := AlertData{RecordBase: RecordBase{1, true}, AlertID: "ALERT-01", BatchID: "TS01-2026-000001", SourceKind: "inspection", SourceID: "INSP-PASS", DeliveryStatus: "SIMULATED", Recipient: "synthetic-regulator", Comment: "synthetic notification"}
	_, err = c.PutAlert(ctx, encodeTest(t, alert))
	assertCode(t, err, "INVALID_ALERT_SOURCE")
	alert.SourceKind = "audit"
	alert.SourceID = "AUDIT-01"
	alert.DeliveryStatus = "SENT"
	_, err = c.PutAlert(ctx, encodeTest(t, alert))
	assertCode(t, err, "INVALID_DELIVERY_STATUS")
	alert.DeliveryStatus = "SIMULATED"
	if _, err = c.PutAlert(ctx, encodeTest(t, alert)); err != nil {
		t.Fatal(err)
	}
}

func TestContractMetadataCanBeConstructed(t *testing.T) {
	if _, err := contractapi.NewChaincode(&TraceContract{}, &ProvenanceContract{}); err != nil {
		t.Fatal(err)
	}
}
