package com.example.bysjdesign.campus.entity;

import javax.persistence.*;
import java.util.Date;

@Entity
@Table(name = "campus_user")
public class CampusUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer userId; // 主键统一为 Integer
    private String studentId;
    private String name;
    private String gender;
    private Integer enrollmentYear;
    private String college;
    private String major;
    private String clazz;
    private Integer status = 1;
    @Temporal(TemporalType.TIMESTAMP)
    private Date createTime;

    public CampusUser() {}

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public Integer getEnrollmentYear() { return enrollmentYear; }
    public void setEnrollmentYear(Integer enrollmentYear) { this.enrollmentYear = enrollmentYear; }

    public String getCollege() { return college; }
    public void setCollege(String college) { this.college = college; }

    public String getMajor() { return major; }
    public void setMajor(String major) { this.major = major; }

    public String getClazz() { return clazz; }
    public void setClazz(String clazz) { this.clazz = clazz; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    /**
     * ✅ 修复点：确保返回类型与属性定义一致
     * 解决编译报错 [106,31] 不兼容的类型：java.lang.Long 无法转换为 java.lang.Integer
     */
    public Integer getId() {
        return userId;
    }
}