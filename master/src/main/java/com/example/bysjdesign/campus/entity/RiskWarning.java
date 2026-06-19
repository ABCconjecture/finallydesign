package com.example.bysjdesign.campus.entity;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * 学业预警与综合风险评估模型
 */
@Entity
@Table(name = "risk_warning",
        indexes = {
                @Index(columnList = "user_id"),
                @Index(columnList = "warning_level"),
                @Index(columnList = "status")
        })
public class RiskWarning implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer warningId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "warning_type", columnDefinition = "VARCHAR(50)")
    private String warningType;

    @Column(name = "warning_level", columnDefinition = "VARCHAR(20)")
    private String warningLevel;

    private Integer riskScore;

    @Column(columnDefinition = "TEXT")
    private String riskDescription;

    @Column(columnDefinition = "VARCHAR(200)")
    private String triggerRule;

    @Column(columnDefinition = "TEXT")
    private String recommendedIntervention;

    @Column(name = "status", nullable = false, columnDefinition = "INT DEFAULT 0")
    private Integer status;

    @Column(columnDefinition = "VARCHAR(50)")
    private String handler;

    @Column(columnDefinition = "TEXT")
    private String handlerRemark;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createTime;

    @Temporal(TemporalType.TIMESTAMP)
    private Date handleTime;

    @Temporal(TemporalType.TIMESTAMP)
    private Date updateTime;

    public RiskWarning() {}

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

    public String getWarningType() {
        return warningType;
    }

    public void setWarningType(String warningType) {
        this.warningType = warningType;
    }

    public String getWarningLevel() {
        return warningLevel;
    }

    public void setWarningLevel(String warningLevel) {
        this.warningLevel = warningLevel;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public String getRiskDescription() {
        return riskDescription;
    }

    public void setRiskDescription(String riskDescription) {
        this.riskDescription = riskDescription;
    }

    public String getTriggerRule() {
        return triggerRule;
    }

    public void setTriggerRule(String triggerRule) {
        this.triggerRule = triggerRule;
    }

    public String getRecommendedIntervention() {
        return recommendedIntervention;
    }

    public void setRecommendedIntervention(String recommendedIntervention) {
        this.recommendedIntervention = recommendedIntervention;
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

    public String getHandlerRemark() {
        return handlerRemark;
    }

    public void setHandlerRemark(String handlerRemark) {
        this.handlerRemark = handlerRemark;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getHandleTime() {
        return handleTime;
    }

    public void setHandleTime(Date handleTime) {
        this.handleTime = handleTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public void setDescription(String description) {
        // For backwards compatibility
        this.riskDescription = description;
    }
}
