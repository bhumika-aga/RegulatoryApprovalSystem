-- Regulatory Approval System - Initial Schema
-- Version: 1.0
-- Description: Creates core tables for workflow audit and regulatory requests

-- Workflow Audit Table
CREATE TABLE IF NOT EXISTS workflow_audit
(
    id                     UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    process_instance_id    VARCHAR(64)  NOT NULL,
    process_definition_key VARCHAR(255),
    task_id                VARCHAR(64),
    task_name              VARCHAR(255),
    event_type             VARCHAR(50)  NOT NULL,
    old_value              TEXT,
    new_value              TEXT,
    performed_by           VARCHAR(100) NOT NULL,
    role                   VARCHAR(50),
    comment                TEXT,
    timestamp              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address             VARCHAR(45),
    additional_data        TEXT
);

-- Indexes for workflow_audit
CREATE INDEX IF NOT EXISTS idx_audit_process_instance ON workflow_audit (process_instance_id);
CREATE INDEX IF NOT EXISTS idx_audit_task_id ON workflow_audit (task_id);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON workflow_audit (timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_performed_by ON workflow_audit (performed_by);
CREATE INDEX IF NOT EXISTS idx_audit_event_type ON workflow_audit (event_type);

-- Regulatory Request Table
CREATE TABLE IF NOT EXISTS regulatory_request
(
    id                  UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    process_instance_id VARCHAR(64) UNIQUE,
    request_title       VARCHAR(255) NOT NULL,
    request_description TEXT,
    request_type        VARCHAR(100) NOT NULL,
    department          VARCHAR(100),
    priority            VARCHAR(20)           DEFAULT 'NORMAL',
    status              VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    compliance_result   VARCHAR(30),
    risk_score          INTEGER,
    submitter_id        VARCHAR(100) NOT NULL,
    submitter_name      VARCHAR(200),
    current_assignee    VARCHAR(100),
    current_stage       VARCHAR(100),
    reviewer_decision   VARCHAR(20),
    reviewer_comment    TEXT,
    manager_decision    VARCHAR(20),
    manager_comment     TEXT,
    compliance_comment  TEXT,
    final_decision      VARCHAR(20),
    final_comment       TEXT,
    rejection_reason    TEXT,
    escalated           BOOLEAN               DEFAULT FALSE,
    escalation_reason   TEXT,
    escalated_at        TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP,
    due_date            TIMESTAMP,
    version             BIGINT                DEFAULT 0
);

-- Indexes for regulatory_request
CREATE INDEX IF NOT EXISTS idx_request_process_instance ON regulatory_request (process_instance_id);
CREATE INDEX IF NOT EXISTS idx_request_status ON regulatory_request (status);
CREATE INDEX IF NOT EXISTS idx_request_submitter ON regulatory_request (submitter_id);
CREATE INDEX IF NOT EXISTS idx_request_created ON regulatory_request (created_at);
CREATE INDEX IF NOT EXISTS idx_request_current_stage ON regulatory_request (current_stage);
CREATE INDEX IF NOT EXISTS idx_request_department ON regulatory_request (department);

-- Application User Table (for local user management if needed)
CREATE TABLE IF NOT EXISTS app_user
(
    id         UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    username   VARCHAR(100) NOT NULL UNIQUE,
    email      VARCHAR(255) NOT NULL UNIQUE,
    full_name  VARCHAR(200),
    department VARCHAR(100),
    roles      VARCHAR(500) NOT NULL,
    active     BOOLEAN               DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP             DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_username ON app_user (username);
CREATE INDEX IF NOT EXISTS idx_user_department ON app_user (department);

-- SLA Configuration Table
CREATE TABLE IF NOT EXISTS sla_configuration
(
    id                        UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    stage_name                VARCHAR(100) NOT NULL UNIQUE,
    sla_duration_hours        INTEGER      NOT NULL,
    escalation_target_role    VARCHAR(50)  NOT NULL,
    notification_before_hours INTEGER               DEFAULT 2,
    active                    BOOLEAN               DEFAULT TRUE,
    created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP             DEFAULT CURRENT_TIMESTAMP
);

-- Insert default SLA configurations
INSERT INTO sla_configuration (stage_name, sla_duration_hours, escalation_target_role, notification_before_hours)
VALUES ('INITIAL_REVIEW', 8, 'MANAGER', 2),
       ('MANAGER_APPROVAL', 24, 'SENIOR_MANAGER', 4),
       ('COMPLIANCE_CHECK', 48, 'COMPLIANCE', 8),
       ('FINAL_APPROVAL', 8, 'ADMIN', 2) ON CONFLICT (stage_name) DO NOTHING;

-- Comments for documentation
COMMENT ON TABLE workflow_audit IS 'Stores all audit events for workflow activities';
COMMENT ON TABLE regulatory_request IS 'Main table storing regulatory approval requests';
COMMENT ON TABLE sla_configuration IS 'Configuration for SLA timers per workflow stage';
COMMENT ON COLUMN workflow_audit.event_type IS 'Type of audit event: WORKFLOW_STARTED, TASK_CREATED, TASK_CLAIMED, TASK_COMPLETED, TASK_ESCALATED, etc.';
COMMENT ON COLUMN regulatory_request.status IS 'Current status: PENDING, IN_REVIEW, APPROVED, REJECTED, ESCALATED, COMPLETED, TERMINATED';
