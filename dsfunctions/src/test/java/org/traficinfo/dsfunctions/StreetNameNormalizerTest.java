package org.traficinfo.dsfunctions;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class StreetNameNormalizerTest {
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
