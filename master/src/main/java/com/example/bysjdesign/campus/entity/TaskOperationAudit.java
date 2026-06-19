package com.example.bysjdesign.campus.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import java.io.Serializable;
import java.util.Date;

@Entity
@Table(name = "task_operation_audit")
public class TaskOperationAudit implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_key", nullable = false, length = 64)
    private String taskKey;

    @Column(name = "task_name", nullable = false, length = 100)
    private String taskName;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "result", nullable = false, length = 20)
    private String result;

    @Column(name = "operator_user_id")
    private Integer operatorUserId;

    @Column(name = "operator_username", length = 100)
    private String operatorUsername;

    @Column(name = "operator_display_name", length = 100)
    private String operatorDisplayName;

    @Column(name = "operator_role", length = 20)
    private String operatorRole;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "scheduler_instance_id", length = 190)
    private String schedulerInstanceId;

    @Column(name = "node_id", length = 190)
    private String nodeId;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "create_time", nullable = false)
    private Date createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskKey() {
        return taskKey;
    }

    public void setTaskKey(String taskKey) {
        this.taskKey = taskKey;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Integer getOperatorUserId() {
        return operatorUserId;
    }

    public void setOperatorUserId(Integer operatorUserId) {
        this.operatorUserId = operatorUserId;
    }

    public String getOperatorUsername() {
        return operatorUsername;
    }

    public void setOperatorUsername(String operatorUsername) {
        this.operatorUsername = operatorUsername;
    }

    public String getOperatorDisplayName() {
        return operatorDisplayName;
    }

    public void setOperatorDisplayName(String operatorDisplayName) {
        this.operatorDisplayName = operatorDisplayName;
    }

    public String getOperatorRole() {
        return operatorRole;
    }

    public void setOperatorRole(String operatorRole) {
        this.operatorRole = operatorRole;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getSchedulerInstanceId() {
        return schedulerInstanceId;
    }

    public void setSchedulerInstanceId(String schedulerInstanceId) {
        this.schedulerInstanceId = schedulerInstanceId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
