package com.example.bysjdesign.campus.entity;

import lombok.Data;
import javax.persistence.*;
import java.util.Date;

@Data
@Entity
@Table(name = "access_log")
public class AccessLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long accessId;
    private Integer userId;
    private String locationType;
    private String locationName;
    @Temporal(TemporalType.TIMESTAMP)
    private Date entryTime;
    @Temporal(TemporalType.TIMESTAMP)
    private Date exitTime;
    private Integer durationSec;
    private String cardId;
    private String deviceId;
}