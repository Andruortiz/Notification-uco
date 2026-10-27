package co.edu.uco.notification.core.domain.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmsSegmenterTest {

  private static final String LONG_GSM_160 =
      "Hola Ana, tu pedido ya esta listo para retirar en la sede central del campus norte. "
          + "Gracias por tu compra y hasta pronto, te esperamos en el mostrador! Gracias.";

  @Test
  void asciiTextFitsOneGsmSegment() {
    final SmsSegmentation sms = SmsSegmenter.segment("Hola Ana, tu codigo es 123456");

    assertEquals(SmsEncoding.GSM7, sms.encoding());
    assertEquals(29, sms.units());
    assertEquals(160, sms.unitsPerSegment());
    assertEquals(1, sms.segments());
    assertFalse(sms.exceedsSingleSegment());
  }

  @Test
  void accentedVowelForcesUcs2WithSeventyUnits() {
    final SmsSegmentation sms = SmsSegmenter.segment("Hola Ana, tu código es 123456");

    assertEquals(SmsEncoding.UCS2, sms.encoding());
    assertEquals(29, sms.units());
    assertEquals(70, sms.unitsPerSegment());
    assertEquals(1, sms.segments());
  }

  @Test
  void enyeIsInGsmButAcuteAIsNot() {
    final SmsSegmentation enye = SmsSegmenter.segment("Tu contraseña es 1234");
    final SmsSegmentation acute = SmsSegmenter.segment("contraseña, tu codigo está listo");

    assertEquals(SmsEncoding.GSM7, enye.encoding());
    assertEquals(21, enye.units());
    assertEquals(SmsEncoding.UCS2, acute.encoding());
  }

  @Test
  void seventyUcs2UnitsFitOneSegmentAndSeventyOneNeedTwo() {
    final String seventy = "Ana, tu pedido está listo para retirar hoy en la sede norte del campus";
    final SmsSegmentation one = SmsSegmenter.segment(seventy);

    assertEquals(SmsEncoding.UCS2, one.encoding());
    assertEquals(70, one.units());
    assertEquals(1, one.segments());
    assertFalse(one.exceedsSingleSegment());

    final SmsSegmentation two = SmsSegmenter.segment(seventy + ".");

    assertEquals(71, two.units());
    assertEquals(2, two.segments());
    assertEquals(67, two.unitsPerSegment());
    assertTrue(two.exceedsSingleSegment());
  }

  @Test
  void oneHundredSixtyGsmUnitsFitOneSegmentAndOneHundredSixtyOneNeedTwo() {
    final SmsSegmentation one = SmsSegmenter.segment(LONG_GSM_160);

    assertEquals(SmsEncoding.GSM7, one.encoding());
    assertEquals(160, one.units());
    assertEquals(1, one.segments());

    final SmsSegmentation two = SmsSegmenter.segment(LONG_GSM_160.replace("Gracias.", "Gracias!!"));

    assertEquals(161, two.units());
    assertEquals(2, two.segments());
    assertEquals(153, two.unitsPerSegment());
    assertTrue(two.exceedsSingleSegment());
  }

  @Test
  void extensionCharactersCountTwoUnitsEach() {
    final SmsSegmentation sms = SmsSegmenter.segment("Total: 10 € {x}");

    assertEquals(SmsEncoding.GSM7, sms.encoding());
    assertEquals(18, sms.units());
  }

  @Test
  void emojiForcesUcs2AndCountsUtf16Units() {
    final SmsSegmentation sms = SmsSegmenter.segment("Hola Ana 😀 tu cita es el lunes");

    assertEquals(SmsEncoding.UCS2, sms.encoding());
    assertEquals(31, sms.units());
    assertEquals(1, sms.segments());
  }

  @Test
  void emptyTextHasZeroSegments() {
    final SmsSegmentation sms = SmsSegmenter.segment("");

    assertEquals(0, sms.segments());
    assertEquals(0, sms.units());
    assertFalse(sms.exceedsSingleSegment());
  }
}
