package org.traficinfo.dsfunctions;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class StreetNameNormalizerTest {
    @Test
    public void movesParenthesizedStreetTypeTogetherWithItsPreposition() {
        assertEquals("QUAI DU CARENAGE", StreetNameNormalizer.normalize("CARENAGE (QUAI DU)"));
        assertTrue(StreetNameNormalizer.sameName("CARENAGE (QUAI DU)", "Quai du Carénage"));
        assertEquals("AVENUE DU SEIZIEME PORT", StreetNameNormalizer.normalize("SEIZIEME PORT (AVENUE DU)"));
        assertTrue(StreetNameNormalizer.sameName("SEIZIEME PORT (AVENUE DU)", "Avenue du Seizième Port"));
        assertTrue(StreetNameNormalizer.sameName("CARENAGE (QU. DU)", "Quai du Carénage"));
        assertTrue(StreetNameNormalizer.sameName("PAIX (RUE DE LA)", "Rue de la Paix"));
        assertTrue(StreetNameNormalizer.sameName("ECOLE (RUE DE L')", "Rue de l’École"));
        assertTrue(StreetNameNormalizer.sameName("ITALIE (AV. D')", "Avenue d’Italie"));
        assertTrue(StreetNameNormalizer.sameName("FLEURS (ROND POINT DES)", "Rond-point des Fleurs"));
        assertTrue(StreetNameNormalizer.sameName("CYPRES (MONTEE DES)", "Montée des Cyprès"));
        assertFalse(StreetNameNormalizer.sameName("CYPRES (MONTEE DES)", "Montée du Cyprès"));
        assertFalse(StreetNameNormalizer.sameName("CARENAGE (QUAI DU)", "Quai Carénage"));
        assertFalse(StreetNameNormalizer.sameName("CARENAGE (QUAI DU)", "Quai du Port"));
        assertEquals("JEAN JAURES AVENUE PROLONGEE", StreetNameNormalizer.normalize("JEAN JAURES (AVENUE PROLONGEE)"));
    }

    @Test
    public void normalizesNumberedRoadsUsedAsStreetNames() {
        for (String name : List.of("D210a", "D 210 A", "d 0210 a", "D-210-A")) {
            assertEquals(name, "D210A", StreetNameNormalizer.normalize(name));
            assertTrue(StreetNameNormalizer.sameName(name, "D 210 A"));
        }
        assertTrue(StreetNameNormalizer.sameName("D0908E02", "D 908 E2"));
        assertTrue(StreetNameNormalizer.sameName("D8", "D 8"));
        for (String name : List.of("RD135", "RD 135", "rd-00135")) {
            assertTrue(StreetNameNormalizer.sameName(name, "D 135"));
            assertTrue(StreetNameNormalizer.isRoadReference(name));
        }
        assertFalse(StreetNameNormalizer.sameName("RD135", "D 136"));
        assertEquals("D111", StreetNameNormalizer.normalize("départementale 111"));
        assertTrue(StreetNameNormalizer.sameName("départementale 111", "D 111"));
        assertTrue(StreetNameNormalizer.sameName("Route départementale 00111", "D 111"));
        assertTrue(StreetNameNormalizer.sameName("départementale 0210a", "D 210 A"));
        assertTrue(StreetNameNormalizer.isRoadReference("départementale 111"));
        assertFalse(StreetNameNormalizer.sameName("départementale 111", "D 112"));
        assertFalse(StreetNameNormalizer.sameName("départementale 111", "N 111"));
        assertTrue(StreetNameNormalizer.isRoadReference("D8"));
        assertTrue(StreetNameNormalizer.isRoadReference("D210a"));
        assertFalse(StreetNameNormalizer.isRoadReference("Rue D8"));
        assertFalse(StreetNameNormalizer.isRoadReference("Jean Jaurès"));
        assertFalse(StreetNameNormalizer.isRoadReference(null));
        assertTrue(StreetNameNormalizer.sameName("N0010", "N 10"));
        assertTrue(StreetNameNormalizer.sameName("A0009", "A 9"));
        assertTrue(StreetNameNormalizer.sameName("M0210a", "M 210 A"));
        assertFalse(StreetNameNormalizer.sameName("D210a", "D 210 B"));
        assertFalse(StreetNameNormalizer.sameName("D210a", "D 210"));
        assertFalse(StreetNameNormalizer.sameName("D210a", "D 211 A"));
        assertFalse(StreetNameNormalizer.sameName("D210a", "M 210 A"));
    }

    @Test
    public void normalizesFrenchStreetFormattingWithoutChangingTheNameWords() {
        for (String name : List.of("JEAN JAURES (AVENUE)", "Jean Jaurès (av.)", "AV JEAN-JAURES",
                "Avenue Jean Jaurès", "12 bis av. Jean Jaurès")) {
            assertEquals(name, "AVENUE JEAN JAURES", StreetNameNormalizer.normalize(name));
        }
        assertEquals("BOULEVARD GENERAL DE GAULLE", StreetNameNormalizer.normalize("Général de Gaulle (BD)"));
        assertEquals("RUE DE L ECOLE", StreetNameNormalizer.normalize("Rue de l’École"));
        assertEquals("PLACE DU COEUR", StreetNameNormalizer.normalize("Place du Cœur"));
        assertTrue(StreetNameNormalizer.sameName("R. ST PIERRE", "Rue Saint-Pierre"));
        assertEquals("RUE DU 8 MAI 1945", StreetNameNormalizer.normalize("RUE DU 8 MAI 1945"));
        assertEquals("8 MAI 1945", StreetNameNormalizer.normalize("8 MAI 1945"));
        assertEquals("JEAN JAURES PROLONGEE", StreetNameNormalizer.normalize("JEAN JAURES (PROLONGEE)"));
        assertFalse(StreetNameNormalizer.sameName("JEAN JAURES (RUE)", "Avenue Jean Jaurès"));
        assertFalse(StreetNameNormalizer.sameName("RUE DU 8 MAI", "RUE DU 18 MAI"));
        assertFalse(StreetNameNormalizer.sameName("Rue Jean Moulin", "Rue Moulin Jean"));
        assertFalse(StreetNameNormalizer.sameName("", ""));
        assertFalse(StreetNameNormalizer.sameName("---", "---"));
    }
}
