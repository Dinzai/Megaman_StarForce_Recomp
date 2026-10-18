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

    @Test public void zerkerNinjaFormsSetOneTribeStartBit() {
        String c = FormSystem.code(FormSystem.find(GameProfile.ZERKER_NINJA, "saurian"));
        assertTrue(c, c.contains("52143B3C D1052804\nD9000000 0219D2E4\nD4000002 FFFC1FFF\nD4000001 00008000\nD6000000 0219D2E4\nD2000000 00000000\n"));
        assertTrue(FormSystem.code(FormSystem.find(GameProfile.ZERKER_NINJA, "tribe-king")).contains("D4000001 00020000"));
        FormSystem.Spec ace = FormSystem.find(GameProfile.ZERKER_NINJA, "black-ace");
        assertEquals("pl00a", ace.hostModel);
        assertEquals("pl010", ace.model);
        assertFalse(FormSystem.find(GameProfile.ZERKER_NINJA, "zerker").needsModel());
        assertTrue(FormSystem.supports(GameProfile.ZERKER_NINJA));
        assertEquals(9, FormSystem.forms(GameProfile.ZERKER_NINJA).length);
    }

    @Test public void blackAceNoiseFormsSetTheStartingNoise() {
        String c = FormSystem.code(FormSystem.find(GameProfile.BLACK_ACE, "taurus")).replaceAll("(?m)^#.*\n", "");
        assertEquals("52141F20 B089B5F0\n22189876 00000007\nD2000000 00000000\n", c);
        assertTrue(FormSystem.supports(GameProfile.BLACK_ACE));
        assertEquals(13, FormSystem.forms(GameProfile.BLACK_ACE).length);
        for (FormSystem.Spec s : FormSystem.BA_FORMS) assertFalse(s.needsModel());
    }

    @Test public void blackAceFinalizePatchesTheBattleStartMode() {
        String ace = FormSystem.code(FormSystem.find(GameProfile.BLACK_ACE, "black-ace")).replaceAll("(?m)^#.*\n", "");
        assertEquals("52163D94 1C0CB538\n12163DA0 0000220C\n12163DA8 0000210C\n221897C2 00000000\nD2000000 00000000\n", ace);
        assertTrue(FormSystem.code(FormSystem.find(GameProfile.BLACK_ACE, "red-joker")).contains("221897C2 00000001\n"));
    }
}
