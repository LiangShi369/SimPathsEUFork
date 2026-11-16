package simpaths.model.macro;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import simpaths.model.Person;
import simpaths.model.enums.Labour;
import static org.junit.jupiter.api.Assertions.*;

public class RamseyRecursiveFeedbackTest {

    private static final double TOL = 1e-12;

    @Test
    public void testRevisedSliceFullPersistenceScalesWholeTail() {
        double[] projN = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111};
        double[] out = MacroModelManager.buildRevisedLaborSlice(projN, 4, 1.02, 1.0);
        assertEquals(8, out.length);
        for (int j = 0; j < out.length; j++) {
            assertEquals(projN[4 + j] * 1.02, out[j], TOL, "quarter " + j);
        }
    }

    @Test
    public void testRevisedSliceZeroPersistenceScalesRealizedYearOnly() {
        double[] projN = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111};
        double[] out = MacroModelManager.buildRevisedLaborSlice(projN, 0, 1.02, 0.0);
        for (int j = 0; j < 4; j++) assertEquals(projN[j] * 1.02, out[j], TOL);   // 0^0 == 1
        for (int j = 4; j < out.length; j++) assertEquals(projN[j], out[j], TOL); // untouched tail
    }

    @Test
    public void testRevisedSliceGeometricDecayPerYear() {
        double[] projN = new double[16];
        java.util.Arrays.fill(projN, 100.0);
        double rho = 0.5;
        double[] out = MacroModelManager.buildRevisedLaborSlice(projN, 0, 1.10, rho);
        assertEquals(110.0, out[0], TOL);            // year 0: full gap
        assertEquals(105.0, out[4], TOL);            // year 1: half gap
        assertEquals(102.5, out[8], TOL);            // year 2: quarter gap
        assertEquals(out[4], out[7], TOL);           // constant within a year
    }

    @Test
    public void testRevisedSliceNeutralDeltaIsIdentity() {
        double[] projN = {100, 200, 300, 400, 500};
        double[] out = MacroModelManager.buildRevisedLaborSlice(projN, 1, 1.0, 1.0);
        assertArrayEquals(new double[] {200, 300, 400, 500}, out, TOL);
    }

    // ========== Behavior tests: applyRamseyFeedbackWithRealized ==========

    private static final String PL_MODEL_PATH = "input" + java.io.File.separator + "PL"
            + java.io.File.separator + "MacroModel";
    private static final int START_YEAR = 2023;

    private static void assumeRamseyInputsAvailable() {
        java.nio.file.Path macroDir = Paths.get(PL_MODEL_PATH);
        Assumptions.assumeTrue(Files.exists(macroDir.resolve("growth_params_Poland.json"))
                        && Files.exists(macroDir.resolve("growth_terminal_state.json"))
                        && Files.exists(macroDir.resolve("growth_model_reference_poland.csv")),
                "Ramsey MacroModel fixtures are required for RamseyRecursiveFeedbackTest");
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String fieldName, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(target);
        if (type == Double.class) {
            return (T) Double.valueOf(field.getDouble(target));
        }
        if (type == Integer.class) {
            return (T) Integer.valueOf(field.getInt(target));
        }
        return type.cast(value);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private MacroModelManager newFeedbackManager(double persistence) throws Exception {
        assumeRamseyInputsAvailable();
        MacroModelManager m = new MacroModelManager();
        m.setEnabled(true);
        m.setUseDsge(false);
        m.setUseRamseyTrend(true);
        m.setPathRecordingEnabled(false);
        m.setRamseyFeedbackPersistence(persistence);
        setField(m, "simulationStartYear", START_YEAR);
        m.initializeRamseyTrend(PL_MODEL_PATH);
        return m;
    }

    /** Same as {@link #newFeedbackManager(double)}, with a Ramsey scenario loaded. */
    private MacroModelManager newFeedbackManagerWithScenario(double persistence, String scenarioFile)
            throws Exception {
        assumeRamseyInputsAvailable();
        MacroModelManager m = new MacroModelManager();
        m.setEnabled(true);
        m.setUseDsge(false);
        m.setUseRamseyTrend(true);
        m.setPathRecordingEnabled(false);
        m.setRamseyFeedbackPersistence(persistence);
        m.setRamseyScenario(scenarioFile);
        setField(m, "simulationStartYear", START_YEAR);
        m.initializeRamseyTrend(PL_MODEL_PATH);
        return m;
    }

    /** Mean of the 4 quarters of calendar year `year` in the retained original projection. */
    private double assumedMeanN(MacroModelManager m, int year) throws Exception {
        double[] projN = getField(m, "ramseyProjN", double[].class);
        int q0 = 4 * (year - START_YEAR);
        return (projN[q0] + projN[q0 + 1] + projN[q0 + 2] + projN[q0 + 3]) / 4.0;
    }

    private double meanWageAtQuarter(MacroModelManager m, int firstQuarter) throws Exception {
        RamseyTrendModel trend = getField(m, "ramseyTrend", RamseyTrendModel.class);
        double sum = 0.0;
        for (int j = 0; j < 4; j++) sum += trend.getTrendAtQuarter(firstQuarter + j).wage();
        return sum / 4.0;
    }

    @Test
    public void testStartYearIsSkippedAndFirstValidYearLatchesLambda() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        m.applyRamseyFeedbackWithRealized(START_YEAR, 1000.0);       // labour market has not run
        assertTrue(Double.isNaN((Double) getField(m, "ramseyFeedbackLambda", Double.class)));

        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0);   // first post-LM year: latch
        double lambda = getField(m, "ramseyFeedbackLambda", Double.class);
        assertEquals(assumedMeanN(m, START_YEAR + 1) / 1000.0, lambda, 1e-12);
        assertEquals(START_YEAR, (int) getField(m, "ramseyQuarterZeroYear", Integer.class)); // no re-solve
    }

    @Test
    public void testNeutralRealizationLeavesWagePathUnchanged() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0);   // latch
        double neutral2025 = 1000.0 * assumedMeanN(m, START_YEAR + 2) / assumedMeanN(m, START_YEAR + 1);
        double w2026Before = meanWageAtQuarter(m, 12);               // 2026 on the original path
        var baselineBefore = getField(m, "ramseyBaseline", RamseyTrendModel.TrendState.class);

        m.applyRamseyFeedbackWithRealized(START_YEAR + 2, neutral2025); // delta == 1, re-solve fires
        assertEquals(START_YEAR + 2, (int) getField(m, "ramseyQuarterZeroYear", Integer.class));
        double w2026After = meanWageAtQuarter(m, 4);                 // 2026 on the re-solved path
        assertTrue(Math.abs(w2026After / w2026Before - 1.0) < 1e-6,
                "neutral re-solve moved the wage path by " + (w2026After / w2026Before - 1.0));
        assertSame(baselineBefore, getField(m, "ramseyBaseline", RamseyTrendModel.TrendState.class),
                "ramseyBaseline must never be re-latched");
    }

    @Test
    public void testPersistentPositiveSurpriseLowersNextYearWage() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0);
        double neutral2025 = 1000.0 * assumedMeanN(m, START_YEAR + 2) / assumedMeanN(m, START_YEAR + 1);
        double w2026Before = meanWageAtQuarter(m, 12);

        m.applyRamseyFeedbackWithRealized(START_YEAR + 2, neutral2025 * 1.02);
        double w2026After = meanWageAtQuarter(m, 4);
        double revisionPct = (w2026After / w2026Before - 1.0) * 100.0;
        // Capital dilution: +2% permanent labor -> wage dips by roughly alpha*2% (~0.5-0.7%),
        // partially offset within the year by investment. Generous band, strict sign.
        assertTrue(revisionPct < -0.10, "expected a wage dip, got " + revisionPct + "%");
        assertTrue(revisionPct > -1.20, "implausibly large dip: " + revisionPct + "%");
    }

    @Test
    public void testTransitorySurpriseLeavesNextYearWageAlmostUnchanged() throws Exception {
        MacroModelManager m = newFeedbackManager(0.0);               // the literal one-year-only scheme
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0);
        double neutral2025 = 1000.0 * assumedMeanN(m, START_YEAR + 2) / assumedMeanN(m, START_YEAR + 1);
        double w2026Before = meanWageAtQuarter(m, 12);

        m.applyRamseyFeedbackWithRealized(START_YEAR + 2, neutral2025 * 1.02);
        double w2026After = meanWageAtQuarter(m, 4);
        double revisionPct = (w2026After / w2026Before - 1.0) * 100.0;
        // With a transitory belief the future labor path is unchanged; only the (small)
        // extra investment out of one year's windfall moves next year's wage.
        assertTrue(Math.abs(revisionPct) < 0.10,
                "transitory belief should give near-zero wage revision, got " + revisionPct + "%");
    }

    /**
     * Regression guard for the single {@code ramseyTrend.stepYear()} call inside the re-solve
     * branch. {@link #meanWageAtQuarter} is absolute-index and cursor-independent, so it cannot
     * detect a missing {@code stepYear()} call (the cursor-independent assertions above stay
     * green even if that line is deleted). {@code getCurrentQuarterIdx()} and
     * {@code advanceRamseyTrend()} are both cursor-driven, so they fail loudly instead.
     */
    @Test
    public void testReSolveAdvancesCursorSoNextAdvanceStepsFollowingYear() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0);   // latch
        double neutral2025 = 1000.0 * assumedMeanN(m, START_YEAR + 2) / assumedMeanN(m, START_YEAR + 1);

        m.applyRamseyFeedbackWithRealized(START_YEAR + 2, neutral2025); // delta == 1, re-solve fires
        RamseyTrendModel trend = getField(m, "ramseyTrend", RamseyTrendModel.class);
        assertEquals(4, trend.getCurrentQuarterIdx(),
                "solveForPath resets the cursor to 0; the re-solve's single stepYear() call must "
                + "consume the realized year (2025) and leave the cursor at quarter 4 so the next "
                + "advanceRamseyTrend() steps 2026, not 2025 again");

        double expectedWage2026 = meanWageAtQuarter(m, 4);           // 2026 on the re-solved path
        m.advanceRamseyTrend();                                      // the wrapper's next-tick call
        double steppedWage2026 =
                getField(m, "ramseyCurrentYearState", RamseyTrendModel.TrendState.class).wage();
        assertEquals(expectedWage2026, steppedWage2026, Math.abs(expectedWage2026) * 1e-9,
                "advanceRamseyTrend() after the re-solve must step quarters 4-7 (2026); "
                + "a missing stepYear() would instead re-consume quarters 0-3 (2025)");
    }

    /**
     * Scenario choice: {@code _macroScenario_ramsey_high_gA_2040.csv} declares a single,
     * permanent +0.25pp g_A step landing in 2040 (0 before, +0.0025 forever after) — checked
     * before picking it, since a flat/constant deviation (e.g. the plain "_ramsey_high_gA.csv"
     * variant, +0.0025 from 2023 onward with no time variation) would be invariant to a 2-year
     * calendar mis-anchor and make the assertion vacuous.
     *
     * <p>The re-solve's quarter 0 is real calendar year 2025. A correct re-anchor
     * ({@code setScenario(ramseyScenarioObj, year)}) places the step at real 2040, exactly
     * where the baseline (correctly anchored at simulationStartYear=2023) also places it — so
     * a NEUTRAL realization must still reproduce the baseline wage path to the same 1e-6 band
     * used without a scenario. Dropping the re-anchor leaves {@code scenarioStartYear} stale at
     * 2023, so the re-solved households would instead see the step land 2 years late (real
     * 2042), visibly moving the perfect-foresight path — confirmed by mutation, see the task
     * report.
     */
    @Test
    public void testNeutralRealizationWithScenarioReproducesOriginalPath() throws Exception {
        MacroModelManager m = newFeedbackManagerWithScenario(1.0, "_macroScenario_ramsey_high_gA_2040.csv");
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0);   // latch
        double neutral2025 = 1000.0 * assumedMeanN(m, START_YEAR + 2) / assumedMeanN(m, START_YEAR + 1);
        double w2026Before = meanWageAtQuarter(m, 12);                // 2026 on the original path

        m.applyRamseyFeedbackWithRealized(START_YEAR + 2, neutral2025); // delta == 1, re-solve fires
        double w2026After = meanWageAtQuarter(m, 4);                  // 2026 on the re-solved path
        assertTrue(Math.abs(w2026After / w2026Before - 1.0) < 1e-6,
                "neutral re-solve under an active scenario moved the wage path by "
                + (w2026After / w2026Before - 1.0) + " -- scenario re-anchor may be broken");
    }

    // ========== Public wrapper: applyRamseyRecursiveFeedback(int, Set<Person>) ==========

    /**
     * Two at-risk workers (ages 30/45, weight 1.0 each, hours > MIN_HOURS_WORKING) and one
     * out-of-working-age person (age 70) who must be excluded by the manager's age filter
     * before the hours test ever runs. Person is mocked rather than constructed for real:
     * {@link LaborSupplyAggregator#computeRawAggregates} only ever calls a handful of getters
     * on it (getWeight, getDag, atRiskOfWork, getLabourSupplyWeekly, getLabourSupplyHoursWeekly)
     * and none of Person's own heavy construction machinery, so a full real Person (as
     * LaborSupplyAggregatorTest's class javadoc notes is otherwise needed) is not required here.
     */
    private static Set<Person> buildSyntheticPersons() {
        Person worker1 = Mockito.mock(Person.class);
        Mockito.when(worker1.getWeight()).thenReturn(1.0);
        Mockito.when(worker1.getDag()).thenReturn(30);
        Mockito.when(worker1.atRiskOfWork()).thenReturn(true);
        Mockito.when(worker1.getLabourSupplyWeekly()).thenReturn(Labour.ZERO);
        Mockito.when(worker1.getLabourSupplyHoursWeekly()).thenReturn(40);

        Person worker2 = Mockito.mock(Person.class);
        Mockito.when(worker2.getWeight()).thenReturn(1.0);
        Mockito.when(worker2.getDag()).thenReturn(45);
        Mockito.when(worker2.atRiskOfWork()).thenReturn(true);
        Mockito.when(worker2.getLabourSupplyWeekly()).thenReturn(Labour.ZERO);
        Mockito.when(worker2.getLabourSupplyHoursWeekly()).thenReturn(20);

        Person outOfWorkingAge = Mockito.mock(Person.class);
        Mockito.when(outOfWorkingAge.getWeight()).thenReturn(1.0);
        Mockito.when(outOfWorkingAge.getDag()).thenReturn(70);
        Mockito.when(outOfWorkingAge.atRiskOfWork()).thenReturn(false);
        Mockito.when(outOfWorkingAge.getLabourSupplyWeekly()).thenReturn(Labour.ZERO);
        Mockito.when(outOfWorkingAge.getLabourSupplyHoursWeekly()).thenReturn(0);

        return new HashSet<>(java.util.List.of(worker1, worker2, outOfWorkingAge));
    }

    /**
     * CONTRACT: the public wrapper is the only tested caller of
     * {@code laborSupplyAggregator.computeRawAggregates(persons).laborForce()} — every other
     * test in this class and in MacroModelManagerTest calls the package-private
     * {@code applyRamseyFeedbackWithRealized(int, double)} directly, bypassing both the
     * {@code !enabled} gate and the aggregation call. Swapping {@code .laborForce()} for a
     * different accessor of the same numeric type on the aggregates record would leave every
     * other test green; only this one pins the choice.
     */
    @Test
    public void testPublicWrapperLatchesLambdaFromComputeRawAggregatesLaborForce() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        // initializeRamseyTrend() alone (unlike the private initialize()) does not construct
        // the aggregator; the production path always goes through initialize() first.
        setField(m, "laborSupplyAggregator", new LaborSupplyAggregator());

        Set<Person> persons = buildSyntheticPersons();
        m.applyRamseyRecursiveFeedback(START_YEAR + 1, persons);

        LaborSupplyAggregator aggregator = getField(m, "laborSupplyAggregator", LaborSupplyAggregator.class);
        double laborForce = aggregator.computeRawAggregates(persons).laborForce();
        double expectedLambda = assumedMeanN(m, START_YEAR + 1) / laborForce;

        double lambda = getField(m, "ramseyFeedbackLambda", Double.class);
        assertEquals(expectedLambda, lambda, 1e-9);
    }

    /** The wrapper must gate on the enabled flag before touching the aggregator or the trend. */
    @Test
    public void testPublicWrapperNoOpWhenManagerDisabled() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        setField(m, "laborSupplyAggregator", new LaborSupplyAggregator());
        m.setEnabled(false);

        m.applyRamseyRecursiveFeedback(START_YEAR + 1, buildSyntheticPersons());

        assertTrue(Double.isNaN((Double) getField(m, "ramseyFeedbackLambda", Double.class)));
    }

    // ========== Hours feedback ==========

    /** Mean of the 4 quarters of calendar year `year` in the retained original hours path. */
    private double assumedMeanH(MacroModelManager m, int year) throws Exception {
        double[] projH = getField(m, "ramseyProjH", double[].class);
        int q0 = 4 * (year - START_YEAR);
        return (projH[q0] + projH[q0 + 1] + projH[q0 + 2] + projH[q0 + 3]) / 4.0;
    }

    /**
     * The hours reference is SimPaths' own latch-year level, not a ratio to the planner's
     * hours path. See the contract note on the three-argument
     * {@code applyRamseyFeedbackWithRealized} for why the two gaps use different references.
     */
    @Test
    public void testHoursReferenceLatchesSimPathsOwnLevel() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0, 40.0);

        assertEquals(40.0, (double) getField(m, "ramseyFeedbackHoursBase", Double.class), 1e-12);
    }

    /**
     * The load-bearing property of the hours reference. The planner's hours path declines at
     * the fitted log-linear trend while SimPaths models no hours trend, so unchanged SimPaths
     * hours must leave the planner's path alone rather than register as a multi-percent
     * labour-supply surprise. This test fails if the gap is ever re-based on the projection.
     */
    @Test
    public void testFlatSimPathsHoursLeaveTheDecliningProjectionAlone() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        // The planner's own hours path really does decline, or the test proves nothing.
        assertTrue(assumedMeanH(m, START_YEAR + 2) < assumedMeanH(m, START_YEAR + 1),
                "fixture no longer has a declining hours projection; this test is vacuous");

        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0, 40.0);
        double neutralN = 1000.0 * assumedMeanN(m, START_YEAR + 2) / assumedMeanN(m, START_YEAR + 1);
        double w2026Before = meanWageAtQuarter(m, 12);

        m.applyRamseyFeedbackWithRealized(START_YEAR + 2, neutralN, 40.0);   // hours unchanged

        double w2026After = meanWageAtQuarter(m, 4);
        assertTrue(Math.abs(w2026After / w2026Before - 1.0) < 1e-6,
                "flat SimPaths hours moved the wage path by " + (w2026After / w2026Before - 1.0));
    }

    /**
     * The Ramsey labor input is N*h, so a persistent +2% hours surprise must dilute capital
     * per efficiency unit exactly as a +2% employment surprise does. Same band as
     * {@link #testPersistentPositiveSurpriseLowersNextYearWage}.
     */
    @Test
    public void testPersistentPositiveHoursSurpriseLowersNextYearWage() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0, 40.0);
        double neutralN = 1000.0 * assumedMeanN(m, START_YEAR + 2) / assumedMeanN(m, START_YEAR + 1);
        double w2026Before = meanWageAtQuarter(m, 12);

        m.applyRamseyFeedbackWithRealized(START_YEAR + 2, neutralN, 40.0 * 1.02);

        double revisionPct = (meanWageAtQuarter(m, 4) / w2026Before - 1.0) * 100.0;
        assertTrue(revisionPct < -0.10, "expected a wage dip, got " + revisionPct + "%");
        assertTrue(revisionPct > -1.20, "implausibly large dip: " + revisionPct + "%");
    }

    /** The two-argument overload must behave exactly like the old employment-only call. */
    @Test
    public void testTwoArgOverloadIsEmploymentOnly() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0);

        assertTrue(Double.isNaN((Double) getField(m, "ramseyFeedbackHoursBase", Double.class)));
    }

    /** A non-finite or non-positive hours aggregate must not latch or perturb anything. */
    @Test
    public void testInvalidRealizedHoursLeavesHoursChannelInert() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0, 0.0);

        assertTrue(Double.isNaN((Double) getField(m, "ramseyFeedbackHoursBase", Double.class)));
        assertFalse(Double.isNaN((Double) getField(m, "ramseyFeedbackLambda", Double.class)));
    }

    /** Both references latch in the first labour-market year, so both gaps are measured. */
    @Test
    public void testBothReferencesLatchInTheFirstLabourMarketYear() throws Exception {
        MacroModelManager m = newFeedbackManager(1.0);
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0, 40.0);

        assertFalse(Double.isNaN((Double) getField(m, "ramseyFeedbackLambda", Double.class)),
                "the employment reference must latch");
        assertEquals(40.0, (Double) getField(m, "ramseyFeedbackHoursBase", Double.class), 1e-12);
    }

    // ========== Always on, one persistence ==========

    /**
     * A Ramsey run whose config names no feedback key feeds back both margins: lambda latches
     * in the first labour-market year and an employment surprise re-solves the path.
     */
    @Test
    public void testFeedbackRunsWithoutAnyFeedbackKey() throws Exception {
        assumeRamseyInputsAvailable();
        MacroModelManager m = new MacroModelManager();
        m.setEnabled(true); m.setUseDsge(false); m.setUseRamseyTrend(true); m.setPathRecordingEnabled(false);
        setField(m, "simulationStartYear", START_YEAR);
        m.initializeRamseyTrend(PL_MODEL_PATH);
        m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0, 40.0);
        assertFalse(Double.isNaN((Double) getField(m, "ramseyFeedbackLambda", Double.class)));
        double onProjection = 1000.0 * assumedMeanN(m, START_YEAR + 2) / assumedMeanN(m, START_YEAR + 1);
        m.applyRamseyFeedbackWithRealized(START_YEAR + 2, 1.02 * onProjection, 40.0);
        assertEquals(START_YEAR + 2, (int) getField(m, "ramseyQuarterZeroYear", Integer.class), "re-solved");
    }

    /**
     * {@code mm_ramseyFeedbackPersistence} governs the hours gap too: with employment on the
     * projection and hours 5% up, a more permanent belief gives a lower next-year trend wage.
     */
    @Test
    public void testHoursGapUsesTheOnePersistence() throws Exception {
        double[] nextYearWage = new double[3];
        double[] rhos = {0.0, 0.5, 1.0};
        for (int i = 0; i < rhos.length; i++) {
            MacroModelManager m = newFeedbackManager(rhos[i]);
            m.applyRamseyFeedbackWithRealized(START_YEAR + 1, 1000.0, 40.0);
            double onProjection = 1000.0 * assumedMeanN(m, START_YEAR + 2) / assumedMeanN(m, START_YEAR + 1);
            m.applyRamseyFeedbackWithRealized(START_YEAR + 2, onProjection, 42.0);   // hours 5% up, employment on projection
            nextYearWage[i] = meanWageAtQuarter(m, 4);
        }
        assertTrue(nextYearWage[2] < nextYearWage[1] && nextYearWage[1] < nextYearWage[0],
                "more permanent hours, lower next-year trend wage: " + java.util.Arrays.toString(nextYearWage));
    }
}
