
package nmk_backend.repository;

import nmk_backend.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findAllByOrderByAppointmentDateAscAppointmentTimeAsc();

    Page<Appointment> findAllByOrderByAppointmentDateAscAppointmentTimeAscIdAsc(Pageable pageable);

    boolean existsByAppointmentDateAndAppointmentTimeAndStatusNot(
            java.time.LocalDate date,
            java.time.LocalTime time,
            String status
    );
}
