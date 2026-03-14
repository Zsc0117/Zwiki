package com.zwiki.repository.dao;

import com.zwiki.repository.entity.ZwikiUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ZwikiUserRepository extends JpaRepository<ZwikiUser, Long> {

    Optional<ZwikiUser> findFirstByUserId(String userId);
}
