package io.dinzai.starforce;

import org.junit.Test;
import static org.junit.Assert.*;

public class FormSystemTest {
    @Test public void sf1FormsOnlySetStartingForm() {
        String c = FormSystem.code(FormSystem.find("dragon"));
        assertTrue(c.contains("5217600C 1C05B570\n221AF1BF 00000003\n"));
        assertFalse(c.contains("221AF022"));
        assertTrue(c.trim().endsWith("D2000000 00000000"));
    }

    @Test public void otherFormsOverrideChargeAndElementWhileTransformed() {
        String c = FormSystem.code(FormSystem.find("zerker")).replaceAll("(?m)^#.*\n", "");
        assertTrue(c.contains("221AF1BF 00000002\nA21AF022 00FF0000\n221AF022 00000004\n221ADC95 00000003\n"));
        assertTrue(FormSystem.find("black-ace").needsModel());
        assertEquals("pl010", FormSystem.find("black-ace").model);
    }

    @Test public void everyFormHasAUniqueId() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (FormSystem.Spec s : FormSystem.FORMS) assertTrue(ids.add(s.id));
        assertEquals(8, ids.size());
    }
}
