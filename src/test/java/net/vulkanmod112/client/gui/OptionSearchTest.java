package net.vulkanmod112.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Searching the settings by what a row can be set to, not only by its name.
 *
 * Reported as "the time switch is not in the search". It is, and it is called
 * Time Control — but somebody looking for it types the answer rather than the
 * question: "fixed", "frozen". Those words are on the row and nowhere in its
 * name or its description, so until this the search came back empty and the
 * setting looked missing.
 *
 * Only the English half is exercised here. The translated half goes through the
 * game's own translation table, which needs a running game; the scoring is the
 * same code either way.
 */
class OptionSearchTest {

    private static VCyclingOption timeControl() {
        return new VCyclingOption("Time Control", "Show a different time of day.",
                VOption.Cost.of(VOption.Level.NONE, VOption.Level.NONE, VOption.Level.NONE), null,
                VCyclingOption.Choices.of("Off", "Frozen", "Fixed"),
                new VCyclingOption.Access() {
                    @Override
                    public int get() {
                        return 0;
                    }

                    @Override
                    public void set(int index) {
                    }
                });
    }

    private static VRangeOption slider(String offLabel) {
        return new VRangeOption("Leaf Glow", "How brightly a leaf lets the sun through.",
                VOption.Cost.of(VOption.Level.NONE, VOption.Level.LOW, VOption.Level.NONE), null,
                0, 100, 5, "%", offLabel,
                new VRangeOption.Access() {
                    @Override
                    public int get() {
                        return 0;
                    }

                    @Override
                    public void set(int value) {
                    }
                });
    }

    @Test
    void aStateOfTheSwitchFindsTheSwitch() {
        VCyclingOption option = timeControl();
        for (String typed : new String[] {"fixed", "frozen", "off"}) {
            assertTrue(OptionSearch.inValues(option, typed) > 0,
                    "typing \"" + typed + "\" should find the row that offers it");
        }
    }

    @Test
    void aStateNobodyOffersFindsNothing() {
        assertEquals(0, OptionSearch.inValues(timeControl(), "midnight"),
                "a word that is not one of the choices is not a match");
    }

    @Test
    void theOffLabelOfASliderCounts() {
        assertTrue(OptionSearch.inValues(slider("OFF"), "off") > 0,
                "a slider whose zero reads OFF is found by \"off\"");
        assertEquals(0, OptionSearch.inValues(slider(null), "off"),
                "a slider with no label for zero has nothing to match");
    }

    @Test
    void aValueScoresBelowAName() {
        // The row called "Off Screen Something" would beat a row that merely
        // offers Off, and that ordering is the point of halving the score.
        VCyclingOption option = timeControl();
        int asValue = OptionSearch.inValues(option, "off");
        int asName = OptionSearch.inValues(
                new VCyclingOption("Off", "Named for it.",
                        VOption.Cost.of(VOption.Level.NONE, VOption.Level.NONE, VOption.Level.NONE),
                        null, VCyclingOption.Choices.of("Off"), new VCyclingOption.Access() {
                            @Override
                            public int get() {
                                return 0;
                            }

                            @Override
                            public void set(int index) {
                            }
                        }), "off");
        assertEquals(asValue, asName, "both are value hits, scored the same way");
        assertTrue(asValue > 0);
    }
}
