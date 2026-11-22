package nmk_backend.service;

import nmk_backend.dto.AdminAuditEventResponse;
import nmk_backend.entity.AdminAuditEvent;
import nmk_backend.repository.AdminAuditEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAuditEventServiceTest {
    @Mock
    private AdminAuditEventRepository repository;

    @InjectMocks
    private AdminAuditEventService service;

    @Test
    void recordPersistsEvent() {
        AdminAuditEvent event = new AdminAuditEvent();
        event.setHttpMethod("POST");
        event.setRequestPath("/api/admin/pos/checkout");
        event.setResponseStatus(201);
        event.setOutcome("SUCCESS");

        service.record(event);

        verify(repository).save(event);
    }

    @Test
    void getEventsReturnsPagedSafeResponses() {
        AdminAuditEvent event = new AdminAuditEvent();
        event.setActorUsername("cashier");
        event.setHttpMethod("POST");
        event.setRequestPath("/api/admin/pos/checkout");
        event.setResponseStatus(201);
        event.setOutcome("SUCCESS");
        when(repository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(event)));

        var result = service.getEvents(0, 25);

        assertEquals(1, result.getTotalElements());
        AdminAuditEventResponse response = result.getContent().get(0);
        assertEquals("cashier", response.actorUsername());
        assertEquals("POST", response.httpMethod());
        assertEquals("/api/admin/pos/checkout", response.requestPath());
        assertEquals("SUCCESS", response.outcome());
    }

    @Test
    void rejectsInvalidPageAndSize() {
        assertThrows(IllegalArgumentException.class, () -> service.getEvents(-1, 25));
        assertThrows(IllegalArgumentException.class, () -> service.getEvents(0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.getEvents(0, 101));
        verifyNoInteractions(repository);
    }
}