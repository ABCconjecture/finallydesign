package com.example.bysjdesign.campus.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import java.io.Serializable;
import java.util.Date;

@Entity
@Table(name = "task_execution_state")
public class TaskExecutionState implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "task_key", nullable = false, length = 64)
    private String taskKey;

    @Column(name = "task_name", nullable = false, length = 100)
    private String taskName;

    @Column(name = "last_status", length = 20)
    private String lastStatus;

    @Column(name = "last_processed_count")
    private Integer lastProcessedCount;

    @Column(name = "last_message", length = 500)
    private String lastMessage;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "last_started_time")
    private Date lastStartedTime;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "last_completed_time")
    private Date lastCompletedTime;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "update_time")
    private Date updateTime;

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

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public Integer getLastProcessedCount() {
        return lastProcessedCount;
    }

    public void setLastProcessedCount(Integer lastProcessedCount) {
        this.lastProcessedCount = lastProcessedCount;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public Date getLastStartedTime() {
        return lastStartedTime;
    }

    public void setLastStartedTime(Date lastStartedTime) {
        this.lastStartedTime = lastStartedTime;
    }

    public Date getLastCompletedTime() {
        return lastCompletedTime;
    }

    public void setLastCompletedTime(Date lastCompletedTime) {
        this.lastCompletedTime = lastCompletedTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
