package cl.respirarysoltar.talleres.service;

import cl.respirarysoltar.talleres.api.dto.InscripcionRequest;
import cl.respirarysoltar.talleres.domain.EstadoInscripcion;
import cl.respirarysoltar.talleres.domain.Inscripcion;
import cl.respirarysoltar.talleres.domain.Taller;
import cl.respirarysoltar.talleres.repository.InscripcionRepository;
import cl.respirarysoltar.talleres.repository.TallerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TallerServiceTest {

    @Mock
    private TallerRepository tallerRepository;

    @Mock
    private InscripcionRepository inscripcionRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TallerService service;

    private final UUID tallerId = UUID.randomUUID();
    private Taller taller;

    @BeforeEach
    void setUp() {
        taller = new Taller("Manejo de ansiedad", "Taller grupal", LocalDateTime.now().plusDays(7), 2);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(tallerRepository.findById(tallerId)).thenReturn(Optional.of(taller));
        lenient().when(inscripcionRepository.existsByTallerIdAndPacienteIdAndEstadoIn(any(), any(), any()))
                .thenReturn(false);
    }

    @Test
    void inscribirConCupoDisponibleConfirmaLaInscripcion() {
        when(valueOperations.decrement(anyString())).thenReturn(1L);
        when(inscripcionRepository.save(any(Inscripcion.class))).thenAnswer(inv -> inv.getArgument(0));

        Inscripcion inscripcion = service.inscribir(tallerId, new InscripcionRequest(UUID.randomUUID()));

        assertEquals(EstadoInscripcion.CONFIRMADA, inscripcion.getEstado());
    }

    @Test
    void inscribirSinCupoDejaLaInscripcionEnListaDeEspera() {
        when(valueOperations.decrement(anyString())).thenReturn(-1L);
        when(inscripcionRepository.save(any(Inscripcion.class))).thenAnswer(inv -> inv.getArgument(0));

        Inscripcion inscripcion = service.inscribir(tallerId, new InscripcionRequest(UUID.randomUUID()));

        assertEquals(EstadoInscripcion.EN_ESPERA, inscripcion.getEstado());
    }

    @Test
    void cancelarUnaConfirmadaPromueveAlPrimeroDeLaListaDeEspera() {
        Inscripcion confirmada = new Inscripcion(UUID.randomUUID(), EstadoInscripcion.CONFIRMADA);
        confirmada.setTaller(taller);
        Inscripcion enEspera = new Inscripcion(UUID.randomUUID(), EstadoInscripcion.EN_ESPERA);
        UUID inscripcionId = UUID.randomUUID();
        when(tallerRepository.findById(tallerId)).thenReturn(Optional.of(conId(taller, tallerId)));
        when(inscripcionRepository.findById(inscripcionId)).thenReturn(Optional.of(confirmada));
        when(inscripcionRepository.findFirstByTallerIdAndEstadoOrderByFechaInscripcionAsc(tallerId, EstadoInscripcion.EN_ESPERA))
                .thenReturn(Optional.of(enEspera));

        service.cancelarInscripcion(tallerId, inscripcionId);

        assertEquals(EstadoInscripcion.CANCELADA, confirmada.getEstado());
        assertEquals(EstadoInscripcion.CONFIRMADA, enEspera.getEstado());
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    void cancelarUnaConfirmadaSinListaDeEsperaDevuelveElCupo() {
        Inscripcion confirmada = new Inscripcion(UUID.randomUUID(), EstadoInscripcion.CONFIRMADA);
        confirmada.setTaller(taller);
        UUID inscripcionId = UUID.randomUUID();
        int cupoAntes = taller.getCupoDisponible();
        when(tallerRepository.findById(tallerId)).thenReturn(Optional.of(conId(taller, tallerId)));
        when(inscripcionRepository.findById(inscripcionId)).thenReturn(Optional.of(confirmada));
        when(inscripcionRepository.findFirstByTallerIdAndEstadoOrderByFechaInscripcionAsc(tallerId, EstadoInscripcion.EN_ESPERA))
                .thenReturn(Optional.empty());

        service.cancelarInscripcion(tallerId, inscripcionId);

        assertEquals(cupoAntes + 1, taller.getCupoDisponible());
        verify(valueOperations).increment(anyString());
    }

    /** El taller de prueba no pasa por JPA: se le asigna el id por reflexión. */
    private static Taller conId(Taller taller, UUID id) {
        org.springframework.test.util.ReflectionTestUtils.setField(taller, "id", id);
        return taller;
    }
}
