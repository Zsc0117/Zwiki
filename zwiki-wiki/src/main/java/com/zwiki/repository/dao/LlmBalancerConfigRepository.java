package com.zwiki.repository.dao;

import com.zwiki.repository.entity.LlmBalancerConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LlmBalancerConfigRepository extends JpaRepository<LlmBalancerConfig, Long> {
}
