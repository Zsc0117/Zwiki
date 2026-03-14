package com.zwiki.repository.dao;

import com.zwiki.repository.entity.ZwikiOAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ZwikiOAuthAccountMapper extends JpaRepository<ZwikiOAuthAccount, Long> {

    Optional<ZwikiOAuthAccount> findFirstByProviderAndProviderUserId(String provider, String providerUserId);

    Optional<ZwikiOAuthAccount> findFirstByUserIdAndProvider(String userId, String provider);

    List<ZwikiOAuthAccount> findAllByUserId(String userId);

    long countByUserId(String userId);

    @Transactional
    void deleteByUserIdAndProvider(String userId, String provider);
}
