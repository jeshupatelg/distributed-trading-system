package com.trading.oms.repository;

import com.trading.oms.model.TrackedOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrackedOrderRepository extends JpaRepository<TrackedOrder, String> {
    List<TrackedOrder> findByStatus(String status);
}
