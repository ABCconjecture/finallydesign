package com.example.bysjdesign.campus.entity;

import lombok.Data;
import javax.persistence.*;
import java.util.Date;

@Data
@Entity
@Table(name = "user_profile")
public class UserProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer profileId;
    private Integer userId;
    private Integer clusterId;
    @Column(columnDefinition = "json")
    private String tags;
    @Column(columnDefinition = "text")
    private String featureVector;
    @Temporal(TemporalType.TIMESTAMP)
    private Date updateTime;

    public UserProfile orElse(Object o) {
        return null;
    }

    public void setProfileTags(String generateProfileTags) {
    }
}