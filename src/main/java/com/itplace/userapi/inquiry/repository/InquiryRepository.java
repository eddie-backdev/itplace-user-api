package com.itplace.userapi.inquiry.repository;

import com.itplace.userapi.inquiry.entity.Inquiry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {
}
