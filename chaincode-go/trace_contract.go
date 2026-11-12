// 文件功能：实现 Hyperledger Fabric 链码，保存溯源事件和 IPFS CID，不读取文件内容。
package main

import (
    "encoding/json"
    "fmt"

    "github.com/hyperledger/fabric-contract-api-go/v2/contractapi"
)

type TraceEvent struct {
    TraceID      string `json:"traceId"`
    ObjectID     string `json:"objectId"`
    Stage        string `json:"stage"`
    OperatorID   string `json:"operatorId"`
    EventTime    string `json:"eventTime"`
    ContentCID   string `json:"contentCid"`
    MetadataHash string `json:"metadataHash"`
    Description  string `json:"description"`
}

type TraceContract struct {
    contractapi.Contract
}

// CreateTrace writes the first event for a trace. File bytes never enter the chaincode.
func (c *TraceContract) CreateTrace(ctx contractapi.TransactionContextInterface, eventJSON string) error {
    var event TraceEvent
    if err := json.Unmarshal([]byte(eventJSON), &event); err != nil {
        return fmt.Errorf("invalid event JSON: %w", err)
    }
    if err := validateEvent(event); err != nil {
        return err
    }
    key := "trace:" + event.TraceID
    exists, err := ctx.GetStub().GetState(key)
    if err != nil {
        return err
    }
    if len(exists) != 0 {
        return fmt.Errorf("trace already exists: %s", event.TraceID)
    }
    payload, err := json.Marshal([]TraceEvent{event})
    if err != nil {
        return err
    }
    if err := ctx.GetStub().PutState(key, payload); err != nil {
        return err
    }
    return ctx.GetStub().SetEvent("TraceCreated", payload)
}

// AppendEvent adds a later business-stage event to an existing trace.
func (c *TraceContract) AppendEvent(ctx contractapi.TransactionContextInterface, eventJSON string) error {
    var event TraceEvent
    if err := json.Unmarshal([]byte(eventJSON), &event); err != nil {
        return fmt.Errorf("invalid event JSON: %w", err)
    }
    if err := validateEvent(event); err != nil {
        return err
    }
    key := "trace:" + event.TraceID
    current, err := ctx.GetStub().GetState(key)
    if err != nil {
        return err
    }
    if len(current) == 0 {
        return fmt.Errorf("trace not found: %s", event.TraceID)
    }
    var events []TraceEvent
    if err := json.Unmarshal(current, &events); err != nil {
        return err
    }
    events = append(events, event)
    payload, err := json.Marshal(events)
    if err != nil {
        return err
    }
    if err := ctx.GetStub().PutState(key, payload); err != nil {
        return err
    }
    return ctx.GetStub().SetEvent("TraceAppended", payload)
}

// validateEvent keeps the on-chain schema small and rejects incomplete facts.
func validateEvent(event TraceEvent) error {
    fields := map[string]string{
        "traceId": event.TraceID, "objectId": event.ObjectID, "stage": event.Stage,
        "operatorId": event.OperatorID, "eventTime": event.EventTime,
        "contentCid": event.ContentCID, "metadataHash": event.MetadataHash,
        "description": event.Description,
    }
    for name, value := range fields {
        if value == "" {
            return fmt.Errorf("%s is required", name)
        }
    }
    return nil
}

// QueryTrace returns the event history for one trace.
func (c *TraceContract) QueryTrace(ctx contractapi.TransactionContextInterface, traceID string) ([]TraceEvent, error) {
    payload, err := ctx.GetStub().GetState("trace:" + traceID)
    if err != nil {
        return nil, err
    }
    if len(payload) == 0 {
        return nil, fmt.Errorf("trace not found: %s", traceID)
    }
    var events []TraceEvent
    if err := json.Unmarshal(payload, &events); err != nil {
        return nil, err
    }
    return events, nil
}

func main() {
    chaincode, err := contractapi.NewChaincode(&TraceContract{})
    if err != nil {
        panic(err)
    }
    if err := chaincode.Start(); err != nil {
        panic(err)
    }
}
