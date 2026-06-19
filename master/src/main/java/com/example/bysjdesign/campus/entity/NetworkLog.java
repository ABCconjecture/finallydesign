package com.example.bysjdesign.campus.entity;

import lombok.Data;
import javax.persistence.*;
import java.util.Date;

@Data
@Entity
@Table(name = "network_log")
public class NetworkLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long logId;
    private Integer userId;
    @Temporal(TemporalType.TIMESTAMP)
    private Date sessionStart;
    @Temporal(TemporalType.TIMESTAMP)
    private Date sessionEnd;
    private Integer durationSec;
    private Long uploadBytes;
    private Long downloadBytes;
    private String srcIp;
    private String destDomain;
    private String destIp;
    private String protocol;
    private String category;
    private Integer isAbnormal;

    public long getDataVolume() {
        long upload = uploadBytes == null ? 0L : uploadBytes;
        long download = downloadBytes == null ? 0L : downloadBytes;
        return upload + download;
    }
}
