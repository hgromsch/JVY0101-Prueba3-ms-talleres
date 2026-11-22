package cl.respirarysoltar.talleres.repository;

import cl.respirarysoltar.talleres.domain.Inscripcion;
import cl.respirarysoltar.talleres.domain.EstadoInscripcion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InscripcionRepository extends JpaRepository<Inscripcion, UUID> {

    List<Inscripcion> findByTallerId(UUID tallerId);

    /** Primera persona en la lista de espera (orden de llegada). */
    Optional<Inscripcion> findFirstByTallerIdAndEstadoOrderByFechaInscripcionAsc(UUID tallerId, EstadoInscripcion estado);

    boolean existsByTallerIdAndPacienteIdAndEstadoIn(UUID tallerId, UUID pacienteId, List<EstadoInscripcion> estados);
}
