package com.example.bysjdesign.campus.entity;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_data")
public class AnalysisData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    private LocalDate analysisDate;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 基础指标字段
    private Double avgOnlineHours;
    private Double studyTrafficRatio;
    private Integer libraryAccessCount;
    private Long borrowCount;
    private Double riskScore;
    private Double healthScore;

    // 扩展指标字段
    private Integer networkActivityCount;
    private Integer classroomAccessCount;
    private Integer lateReturnCount;
    private Integer activeDays;
    private Double avgAccessFrequency;
    private Double avgBorrowDays;
    private Integer unreturnedCount;
    private Double networkRisk;
    private Double accessRisk;
    private Double borrowRisk;
    private Boolean abnormalTrafficFlag;
    private Boolean absenteeFlag;

    public AnalysisData() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    // --- 标准 Getter/Setter ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public LocalDate getAnalysisDate() { return analysisDate; }
    public void setAnalysisDate(LocalDate analysisDate) { this.analysisDate = analysisDate; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public Double getAvgOnlineHours() { return avgOnlineHours; }
    public void setAvgOnlineHours(Double avgOnlineHours) { this.avgOnlineHours = avgOnlineHours; }

    public Double getStudyTrafficRatio() { return studyTrafficRatio; }
    public void setStudyTrafficRatio(Double studyTrafficRatio) { this.studyTrafficRatio = studyTrafficRatio; }

    public Integer getLibraryAccessCount() { return libraryAccessCount; }
    public void setLibraryAccessCount(Integer libraryAccessCount) { this.libraryAccessCount = libraryAccessCount; }

    public Long getBorrowCount() { return borrowCount; }
    public void setBorrowCount(Long borrowCount) { this.borrowCount = borrowCount; }

    public Double getRiskScore() { return riskScore; }
    public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }

    public Double getHealthScore() { return healthScore; }
    public void setHealthScore(Double healthScore) { this.healthScore = healthScore; }

    // --- 扩展指标 Getter & Setter (已补全) ---
    public Integer getNetworkActivityCount() { return networkActivityCount; }
    public void setNetworkActivityCount(Integer count) { this.networkActivityCount = count; }

    public Integer getClassroomAccessCount() { return classroomAccessCount; }
    public void setClassroomAccessCount(Integer count) { this.classroomAccessCount = count; }

    public Integer getLateReturnCount() { return lateReturnCount; }
    public void setLateReturnCount(Integer count) { this.lateReturnCount = count; }

    public Integer getActiveDays() { return activeDays; }
    public void setActiveDays(Integer days) { this.activeDays = days; }

    public Double getAvgAccessFrequency() { return avgAccessFrequency; }
    public void setAvgAccessFrequency(Double freq) { this.avgAccessFrequency = freq; }

    public Double getAvgBorrowDays() { return avgBorrowDays; }
    public void setAvgBorrowDays(Double days) { this.avgBorrowDays = days; }

    public Integer getUnreturnedCount() { return unreturnedCount; }
    public void setUnreturnedCount(Integer count) { this.unreturnedCount = count; }

    public Double getNetworkRisk() { return networkRisk; }
    public void setNetworkRisk(Double risk) { this.networkRisk = risk; }

    public Double getAccessRisk() { return accessRisk; }
    public void setAccessRisk(Double risk) { this.accessRisk = risk; }

    public Double getBorrowRisk() { return borrowRisk; }
    public void setBorrowRisk(Double risk) { this.borrowRisk = risk; }

    // --- 逻辑支持方法 ---
    public Double getOverallHealthScore() { return this.healthScore; }

    public Boolean getAbnormalTrafficFlag() { return abnormalTrafficFlag; }
    public void setAbnormalTrafficFlag(Boolean flag) { this.abnormalTrafficFlag = flag; }

    public Boolean getAbsenteeFlag() { return absenteeFlag; }
    public void setAbsenteeFlag(Boolean flag) { this.absenteeFlag = flag; }
}
