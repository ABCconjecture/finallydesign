package com.example.bysjdesign.campus.entity;

import lombok.Data;
import javax.persistence.*;
import java.util.Date;

@Data
@Entity
@Table(name = "borrow_log")
public class BorrowLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long borrowId;
    private Integer userId;
    private String bookIsbn;
    private String bookTitle;
    private String category;
    @Temporal(TemporalType.DATE)
    private Date borrowDate;
    @Temporal(TemporalType.DATE)
    private Date dueDate;
    @Temporal(TemporalType.DATE)
    private Date returnDate;
    private Integer renewCount;
    private String libraryBranch;
}