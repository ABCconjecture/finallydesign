package com.example.bysjdesign.campus.entity;

import javax.persistence.*;
import java.util.Date;

@Entity
@Table(name = "warning_log")
public class WarningLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer warningId;
    private Integer userId;
    private String type;
    private String content;
    @Temporal(TemporalType.TIMESTAMP)
    private Date createTime;
    private Integer status;
    private String handler;
    @Temporal(TemporalType.TIMESTAMP)
    private Date handleTime;

    public WarningLog() {}

    public Integer getWarningId() {
        return warningId;
    }

    public void setWarningId(Integer warningId) {
        this.warningId = warningId;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getHandler() {
        return handler;
    }

    public void setHandler(String handler) {
        this.handler = handler;
    }

    public Date getHandleTime() {
        return handleTime;
    }

    public void setHandleTime(Date handleTime) {
        this.handleTime = handleTime;
    }
}
