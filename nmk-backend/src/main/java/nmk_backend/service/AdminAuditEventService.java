package nmk_backend.service;

import nmk_backend.dto.AdminAuditEventResponse;
import nmk_backend.entity.AdminAuditEvent;
import nmk_backend.repository.AdminAuditEventRepository;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditEventService {
    private final AdminAuditEventRepository repository;

    public AdminAuditEventService(AdminAuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AdminAuditEvent event) {
        repository.save(event);
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditEventResponse> getEvents(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        return repository.findAll(PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(AdminAuditEventResponse::from);
    }
}