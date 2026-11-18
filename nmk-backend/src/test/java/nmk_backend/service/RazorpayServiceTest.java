
package nmk_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class RazorpayServiceTest {

    private RazorpayService razorpayService;

    @BeforeEach
    void setUp() {
        razorpayService = new RazorpayService();

        ReflectionTestUtils.setField(
                razorpayService, "keyId", ""
        );

        ReflectionTestUtils.setField(
                razorpayService, "keySecret", ""
        );

        ReflectionTestUtils.setField(
                razorpayService, "webhookSecret", ""
        );
    }

    @Test
    void requirePaymentCredentialsRejectsMissingKeyId() {

        ReflectionTestUtils.setField(
                razorpayService, "keyId", ""
        );

        ReflectionTestUtils.setField(
                razorpayService, "keySecret", "test_secret"
        );

        assertThrows(
                IllegalStateException.class,
                () -> razorpayService.requirePaymentCredentials()
        );
    }

    @Test
    void requirePaymentCredentialsRejectsMissingKeySecret() {

        ReflectionTestUtils.setField(
                razorpayService, "keyId", "test_key"
        );

        ReflectionTestUtils.setField(
                razorpayService, "keySecret", ""
        );

        assertThrows(
                IllegalStateException.class,
                () -> razorpayService.requirePaymentCredentials()
        );
    }

    @Test
    void requirePaymentCredentialsAcceptsConfiguredCredentials() {

        ReflectionTestUtils.setField(
                razorpayService, "keyId", "test_key"
        );

        ReflectionTestUtils.setField(
                razorpayService, "keySecret", "test_secret"
        );

        assertDoesNotThrow(
                () -> razorpayService.requirePaymentCredentials()
        );
    }

    @Test
    void createOrderRejectsMissingCredentialsBeforeApiCall() {

        assertThrows(
                IllegalStateException.class,
                () -> razorpayService.createOrder(10000)
        );
    }
}
