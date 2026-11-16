# SimPaths Coupling: the Java Side of the Macro Layer

This document describes the Java runtime of the macro layer (`simpaths.model.macro`). The MATLAB/Dynare model
is described in `Technical_Documentation_Macromodel.md`, the contracts between the two sides in
`Integration_Contracts.md`, both in this folder.

---

## 1. Executive Summary

The macro stack has two orthogonal layers:
- **Ramsey growth model (trend layer):** long-run structural path, re-solved each year against the labour
  supply SimPaths realized (§8.1; always on with a Ramsey layer)
- **DSGE state-space model (cycle layer):** quarterly cycle dynamics driven by exogenous shocks only. It is
  one-way: it hands SimPaths the cycle wage and takes no input from SimPaths

```
SimPaths population projection -------> Ramsey trend model (trend layer)
SimPaths realized employment, hours --> Ramsey trend model (yearly re-solve, always on, §8.1)
Scenario shocks (mm_dsgeShockScenario) -> DSGE cycle model (quarterly state-space)
SimPaths receives: delta_w_total = delta_w_cycle + delta_w_trend, delta_pi = delta_pi_cycle
```

### Design principles

1. Opt-in macro layer via `mm_macroModel` (default `off`)  
2. Macro model provides trend (Ramsey growth model) and cycle (DSGE) decomposition; the two layers are selected together by `mm_macroModel`: `off | ramsey | dsge | ramsey_dsge`
3. SimPaths wage application uses total wage effect (trend + cycle changes)
4. The DSGE takes no input from SimPaths. SimPaths' labour supply reaches the macro side through the planner alone, counted once a year after the labour market and the population alignment (§4, §7.1).
5. Path recording is completely decoupled from the macro model usage (controlled via `mm_useMacroPathRecorder`, default `true`).
6. Realized employment and hours are fed back into the Ramsey re-solve whenever a Ramsey layer runs, with one persistence `mm_ramseyFeedbackPersistence` (default `1.0`) for both.

How the macro wage enters SimPaths' wage equation is in §3.3, the order of events within a simulated year and
across a run in §4, and what the feedback assumes about employment in §8.1.

---

## 2. Architecture

### 2.1 Entry and orchestration

- Main integration orchestration: `simpaths.model.macro.MacroModelManager`
- Simulation manager bridge: `simpaths.model.SimPathsModel`
- Dynamic policy evaluator: `simpaths.model.macro.DSGEModel`
- Labour-supply aggregation for the planner's feedback and the level diagnostics: `simpaths.model.macro.LaborSupplyAggregator`

### 2.2 Bundle files consumed

Under `input/{COUNTRY}/MacroModel/` (the bundle the MATLAB exporters write):

- DSGE layer: `policy_A.csv`, `policy_Bs.csv`, `policy_C.csv`, `policy_D.csv`, `shock_params.csv`,
  `model_info.json`
- Ramsey layer: `growth_params_{COUNTRY}.json`, `growth_terminal_state.json`,
  `growth_model_reference_{country}.csv`, `hours_projection_{COUNTRY}.csv` (the forward hours path, §8.1)
  and `population_projection_{COUNTRY}.csv` (the forward `N` path from SimPaths' own demographics). The
  run fails if either projection is missing, rather than extrapolating historical growth.
- Scenarios: `_macroScenario_*.csv`, named by `mm_dsgeShockScenario` / `mm_ramseyScenario` relative to
  this folder. They are maintained by hand; no exporter writes them.

In the bundle but not read at runtime: `steady_state.csv` (the exported model is log-linearised, so every
steady state is zero; kept for inspection), `irfs_eps_*.csv` (read by `DSGEIrfParityTest` and
`CommittedBundlesTest`), `policy_full_gh{u,x}.csv` (for reference; nothing reads them),
`SIMPATHS_INTERFACE.md` (a human-readable summary) and `BUNDLE_PROVENANCE.txt`
(checked by `BundleProvenanceTest`, contract 18). A bundle holding `BUNDLE_NOT_VERIFIED.txt` is refused at
startup.

### 2.3 Ramsey growth input contract

The Ramsey trend layer reads country-specific growth inputs from `growth_params_*.json` together with
`growth_terminal_state.json` and `growth_model_reference_*.csv`.

For TFP growth, `growth_params_*.json` carries `tfp_growth_annual` and an optional `terminal_growth` object:

- Without the object, or with `enabled: false`, the baseline TFP growth rate is `tfp_growth_annual` throughout.
- With `enabled: true`, SimPaths treats `tfp_growth_annual` as the in-sample calibration growth rate and lets it
	converge quarter by quarter toward `terminal_growth.g_A_terminal_annual` at the rate
	`terminal_growth.rho_quarterly` (0.99 when the key is absent).

Example:

```json
"terminal_growth": {
	"enabled": true,
	"g_A_terminal_annual": 0.008,
	"rho_quarterly": 0.99
}
```

---

## 3. Coupling Contract

### 3.1 SimPaths -> DSGE inputs

None. The DSGE takes no input from SimPaths: its annual step (`DSGEModel.stepAnnual(DSGEShockScenario, int)`,
called from `MacroModelManager.runDsgePass`) receives the scenario's shocks for the year and nothing else, all
zero without a scenario. `eps_L` and `eps_h` are shocks of the model like the others, which a scenario may set.
SimPaths' labour supply reaches the macro side through the planner's end-of-year feedback alone (§8.1).

The reason is that nothing SimPaths produces is a business-cycle movement: its workers move with ageing, reforms,
alignment and sampling noise, and those movements reach the planner. Feeding them to the DSGE as well would count
one movement twice, in the trend wage and in the cycle wage.

### 3.2 DSGE -> SimPaths outputs

- `delta_w_cycle`: cyclical wage deviation (%) from DSGE annual aggregation
- `delta_w_trend`: secular wage trend (%) from Ramsey projection
- `delta_w_total = delta_w_cycle + delta_w_trend`
- `delta_pi`: inflation deviation (%) (cycle component)

In SimPaths wage equations, macro feedback is applied as:

`wageMultiplier = 1 + (delta_w_total / 100)`

### 3.3 How the macro wage enters SimPaths, and how that differs from the `wage_growth` index

**Macro model on: one scalar, applied as a multiplier.** Once a year, at `DSGEMacroUpdate` (after union
matching and fertility, before `LabourMarketAndIncomeUpdate`; §4), `MacroModelManager.updateEquilibrium` sets a
single number, the wage deviation `dw` in percent:

| `mm_macroModel` | `dw` |
|---|---|
| `ramsey` | `w_trend(t) = 100 (w(t) / w(q0) - 1)`: the Ramsey trend wage `w = (1-alpha) Y / (N h)`, averaged over the four quarters of year `t`, relative to the handoff quarter `q0`, the first quarter of the start year (`advanceRamseyTrend`). A cumulative level against the start, not a growth rate; already nonzero in the start year |
| `dsge` | `w_cycle(t)`: the DSGE's annual-average wage deviation from its steady state (a 100·log deviation, applied as a percent) |
| `ramsey_dsge` | `w_trend(t) + w_cycle(t)` |

`MacroModelManager.updatePotentialHourlyEarnings` then runs `Person.updateFullTimeHourlyEarnings` for every
person:

1. the log wage from the wage regressions (`W1ma`/`W1fa` for persons not employed last year, without a lag;
   `W1mb`/`W1fb` for persons employed last year, with the lagged log wage), plus the person's random component;
2. `labWageHrlyRegression`, the exponentiated and clamped regression wage before any overlay. This is what
   `updateLaggedVariables` rolls into next year's lag, so the overlay never re-enters the wage equation's own
   AR dynamics. Rolled into the lag, the multiplier would compound every year: a Ramsey trend of about 1.9%/yr
   would be realized as about 5%/yr;
3. the wage times `1 + dw / 100`, the same multiplier for every person, whatever their skill, age, gender or
   region;
4. a clamp to `[MIN_HOURLY_WAGE_RATE, MAX_HOURLY_WAGE_RATE]`, stored as the potential hourly wage `labWageHrly`
   (`setFullTimeHourlyEarningsPotential`). The discrete-choice labour-supply model prices each hours option with
   it; earnings and the tax-benefit donor match follow from the hours chosen.

The macro wage is therefore a multiplicative overlay on the *output* of the wage equation, applied once a year
and kept out of the equation's dynamics. It is a real wage: the Ramsey wage is real, and SimPaths wages are in
`BASE_PRICE_YEAR` prices. It feeds back through the planner: the hours people choose become `L_sim` and `h`
(`LaborSupplyAggregator`), which reach the Ramsey re-solve as `delta_t` and `delta_h` at the end of the year
(§8.1) and move next year's `dw`. They do not reach the DSGE.

**Companion switch.** Whenever the Ramsey layer runs, `Parameters.freezeWageGrowthForRamseyTrend` holds the
exogenous `wage_growth` index (below) at its last in-sample year, `Parameters.wageGrowthLastInSampleYear` (2023
for PL), so that the secular trend is not counted twice. In-sample years are untouched. `SimPathsModel` sets the
flag from `mm_macroModel.usesRamseyTrend()`.

**Macro model off: an index inside the wage equation.** There is no multiplier: step 3 is skipped and
`labWageHrlyRegression` equals `labWageHrly`. The only aggregate wage input is
the real-wage index in `input/{CC}/time_series_factor.xlsx`, sheet `wage_growth`. It is loaded into
`Parameters.upratingIndexMapRealWageGrowth`, rebased to `BASE_PRICE_YEAR` (`rebaseIndexMap`) and extended beyond
its last year at its last observed growth ratio (`extendValueTimeSeries`). It enters wages only as a regressor,
`Person.DoublesVariables.RealWageGrowth` (100 times the index), in all four wage specifications
(`real_wage_growth` in `input_processing/regression_estimates/{CC}/08_reg_wages_{CC}.do`). Its effect is the
estimated coefficient times the index, carried forward by the lag in the `W1mb`/`W1fb` equations, so its
pass-through is partial and is whatever the regression estimated. The `fixTimeTrend` / `timeTrendStopsIn*`
switches freeze the year polynomial regressors, not this index.

**The scenario-workbook variant.** `macroShocksOn`, with `macroShockPopulation`, `macroShockProductivity` and
`macroShockGreenPolicy`, swaps the `wage_growth` sheet for a path from `scenario_macro_shocks.xlsx`
(`wage_{pop}_{prod}[_green]`), together with scenario GDP, population projections and employment-share targets,
to which `ActivityAlignmentMacroShock` aligns employment. It uses the same entry point, the regressor: the path is
an index the wage equation responds to, not a wage level the model is forced to hit. `macroShocksOn` is
independent of `mm_macroModel`.

**Side by side.**

| | Macro off: exogenous index | Macro on: Ramsey / DSGE |
|---|---|---|
| Source | Excel index, extended at its last observed growth ratio | Forward-solved `w = (1-alpha) Y / (N h)`, plus the optional DSGE cycle |
| Entry point | Regressor inside the wage equation | Multiplier on the equation's output |
| Pass-through | Partial, as estimated, shaped by the lag | One for one on the level, by construction |
| Heterogeneity | One index; its effect differs between the `a` and `b` specifications (`W1ma`/`W1fa` vs `W1mb`/`W1fb`) through the lag | The same multiplier for everyone |
| Feedback from SimPaths | None | `L_sim`, `h` → the Ramsey re-solve → next year's wage (the DSGE takes no input) |
| Memory | The lag carries the index's effect forward, as estimated | The overlay is kept out of the lag |
| Out-of-sample trend | From the index's extension | From the Ramsey path; the index is frozen at its last in-sample year |

The exogenous index treats the aggregate wage as a covariate that the micro wage equation responds to as
estimated; the macro layer treats it as a price level that scales everyone's potential wage one for one and that
SimPaths' own labour supply moves. Out of sample the two trends would stack, hence the freeze.

**Simplifications.**

- One multiplier for everyone: the planner produces a single wage, so a macro wage change hits all workers
  equally.
- The planner's wage is compensation per employee, while SimPaths' wage equation is estimated on pooled employee
  and self-employment income (§8.1, "Employees versus all workers").
- The DSGE deviation is a 100·log deviation applied as `1 + x/100`, exact only for small `x`.

---

## 4. Yearly Runtime Flow and Sequence of Events

A simulated year has two link points, with the labour market between them (`SimPathsModel.buildSchedule`).

At `DSGEMacroUpdate`, after union matching and fertility, before `LabourMarketAndIncomeUpdate`:

1. Advance the Ramsey trend one year along the currently solved path, giving `w_trend(t)` (Ramsey modes). From
   `startYear+2` on this requires the end-of-year feedback of `t-1` to have run; otherwise the run stops (§8.1).
2. Step the DSGE four quarters on the scenario's shocks for `t` (zero without a scenario) and average them into
   the annual cycle values `w_cycle(t)`, `pi_cycle(t)` (DSGE modes). The step is the same in every year, the
   start year included (`MacroModelManager.runDsgePass`).
3. Compose the total wage effect `w_trend + w_cycle` and apply it to every person's potential wage (§3.3).
4. Record the year's row, with in-year level indices that step 5 overwrites.

At `MacroEndYearCapture`, after mortality and population alignment:

5. Overwrite the year's recorded level columns with the post-alignment population and, in a Ramsey mode,
   re-solve the remaining Ramsey horizon against the realized employment and hours (§8.1). This is the one count
   of SimPaths' labour supply the macro side uses (§7.1).

Nothing is iterated within the year. The order of steps 1 and 2 does not matter for the wage: neither layer
reads the other's output within the year, and the wage is the sum. The labour market of year `t` reaches wages
in `t+1` through one channel, the planner's re-solve on the post-alignment count; the DSGE never sees it.
The one dependency between the layers is conceptual: the DSGE is estimated on Ramsey-detrended data, so the
Ramsey path is its steady state, which is why `mm_macroModel: dsge` alone is diagnostic (§6.1).

### 4.1 One simulated year

Events in the order `yearlySchedule` fires them, top to bottom. Lavender boxes are SimPaths events, amber the
two link points, blue the macro layer; `dw = w_trend + w_cycle`, in percent.

```mermaid
---
title: One simulated year of the micro-macro link (top to bottom = yearlySchedule order)
config:
  flowchart:
    htmlLabels: false
    wrappingWidth: 400
---
flowchart TB
  A["Aging, retirement,<br/>education, health"]
  W0["UpdatePotentialHourlyEarnings<br/>(with dw of t-1)"]
  U["Cohabitation, union<br/>matching, fertility"]
  M["DSGEMacroUpdate"]
  LM["LabourMarketAndIncomeUpdate<br/>hours, earnings, tax-benefit"]
  B["Benefits, consumption, EDI"]
  P["Mortality, PopulationAlignment"]
  O["UpdateOutputVariables"]
  E["MacroEndYearCapture"]
  Y["EndYear, UpdateYear"]
  R["Ramsey trend<br/>(path solved at the end of t-1)"]
  D["DSGE cycle<br/>(state-space, 4 quarterly steps)"]
  SC["Scenario shocks<br/>(mm_dsgeShockScenario)"]
  RS["Ramsey re-solve<br/>from K(t), A(t)"]

  A ~~~ R
  A --> W0 --> U --> M
  M -- "labWageHrly x (1 + dw/100)<br/>for every person" --> LM
  LM --> B --> P --> O --> E --> Y

  R -- "w_trend(t) = 100 (w(t)/w(q0) - 1)" --> M
  SC -- "shocks of the 4 quarters of t<br/>(zero without a scenario)" --> D
  D -- "w_cycle(t), pi_cycle(t)" --> M
  E -- "L_sim(t), h(t)<br/>(post-labour-market, post-alignment)" --> RS
  RS -- "revised path, used from t+1" --> R

  classDef micro fill:#ede9fe,stroke:#6d28d9;
  classDef link fill:#fde68a,stroke:#b45309,stroke-width:2px;
  classDef macro fill:#dbeafe,stroke:#1d4ed8;
  class A,W0,U,LM,B,P,O,Y micro;
  class M,E link;
  class R,D,SC,RS macro;
```

### 4.2 Message sequence across a run

The start year, the yearly loop and the final export, between the SimPaths schedule and the macro layer.

```mermaid
sequenceDiagram
    autonumber
    participant S as SimPaths schedule
    participant M as MacroModelManager
    participant R as Ramsey trend
    participant D as DSGE cycle
    participant C as MacroPaths.csv

    Note over S,C: startYear (firstYearSched: no labour market, no unions, no fertility)
    S->>M: DSGEMacroUpdate
    M->>M: initialize: verify bundle, latch demographic anchors (Pop, WAP, atRisk)
    M->>D: load state-space, reset to steady state (DSGE modes; dsge alone checks the bundle's handoff quarter)
    M->>R: load bundle, solve the full forward path from (K_T, A_T) with the N and h projections, baseline = quarter 0 (Ramsey modes)
    M->>R: advanceRamseyTrend
    R-->>M: w_trend(startYear)
    M->>D: stepAnnual on the scenario's shocks for startYear (zero without a scenario)
    M->>S: labWageHrly x (1 + dw/100) for all persons
    M->>C: row for startYear
    S->>M: MacroEndYearCapture
    M->>C: overwrite the level columns of the startYear row (post-alignment), no feedback in the start year

    loop every year t from startYear+1 (yearlySchedule)
        Note over S: aging, retirement, education, health, UpdatePotentialHourlyEarnings (dw of t-1), unions, fertility
        S->>M: DSGEMacroUpdate
        opt Ramsey modes
            M->>M: from startYear+2: stop unless the feedback of t-1 ran
            M->>R: advanceRamseyTrend
            R-->>M: w_trend(t), from the path solved at the end of t-1
        end
        opt DSGE modes
            M->>D: stepAnnual on the scenario's shocks for t (zero without a scenario)
            D-->>M: w_cycle(t), pi_cycle(t)
        end
        M->>S: labWageHrly x (1 + (w_trend + w_cycle)/100) for all persons
        M->>C: row t: trend, cycle, wage_realized and the pre-alignment level indices
        S->>S: LabourMarketAndIncomeUpdate: at-risk pool, labour-supply choice at the new wages, earnings, tax-benefit match, disposable income
        opt t = startYear+1
            S->>M: latchStartYearAnchorsPostLabourMarket: l_level and h_level anchors
        end
        Note over S: benefits, consumption, EDI, mortality, PopulationAlignment, output variables
        S->>M: MacroEndYearCapture
        M->>C: overwrite the level columns of row t with the post-alignment population
        opt Ramsey modes
            M->>M: computeRawAggregates: L_sim(t), h(t)
            alt t = startYear+1
                M->>M: latch lambda = N_assumed(t)/L_sim(t) and the hours base
            else from startYear+2
                M->>R: delta = lambda L_sim(t)/N_assumed(t), delta_h, rebuild the remaining path from the original projection, re-solve from K(t), A(t), stepYear
                R-->>M: revised path, cursor at t+1
                M->>C: n_realized_gap, h_realized_gap, w_trend_revision on row t
            end
        end
        Note over S: EndYear, UpdateYear
    end
    S->>M: UpdateYear in endYear: exportPaths
    M->>C: MacroPaths.csv written
```

### 4.3 What crosses the boundary, and when

| When | Direction | What | Computed from | Consumed by | First fires |
|---|---|---|---|---|---|
| Offline (MATLAB) | micro to macro | `population_projection_{CC}.csv` (the employees level at the handoff quarter, grown with the projected population aged 15-64) and `hours_projection_{CC}.csv` | `input/{CC}/align_popProjections.xlsx`, the projection SimPaths' population alignment uses; the hours trend fitted to observed hours | the forward Ramsey solve | before the run |
| `DSGEMacroUpdate` | macro to micro | `w_trend(t)`, cumulative percent against the handoff quarter | the Ramsey path solved at the end of `t-1` (`advanceRamseyTrend`) | the wage multiplier | `startYear` |
| `DSGEMacroUpdate` | macro to micro | `w_cycle(t)`; `pi_cycle(t)` is computed too | the DSGE annual average after four quarterly steps on the scenario's shocks | the wage multiplier (`pi_cycle` is only recorded) | `startYear`; zero without scenario shocks |
| `DSGEMacroUpdate` | macro to micro | every person's potential wage times `1 + (w_trend + w_cycle)/100` | `Person.updateFullTimeHourlyEarnings` (§3.3) | labour-supply choice, earnings, tax-benefit match | `startYear` |
| End of `LabourMarketAndIncomeUpdate` | micro to macro | the `l_level`/`h_level` start anchors, latched once (`latchStartYearAnchorsPostLabourMarket`) | the post-labour-market population | the `l_level`/`h_level` indices and the gate of `participation_rate`, `workers_over_atRiskPool` (§7.2) | `startYear+1`, once |
| `MacroEndYearCapture` | micro to macro | post-alignment level indices (`l_level`, `h_level`, `wap_level`, `cat_*`, `wage_realized`) | `computeRawAggregates` after mortality and population alignment | `MacroPaths.csv` only | `startYear` (`l_level`, `h_level` from `startYear+1`, §7.2) |
| `MacroEndYearCapture` | micro to macro | `L_sim(t)`, `h(t)`; the `lambda` latch, then `delta_t`, `delta_h` and the Ramsey re-solve | the same post-alignment snapshot | the Ramsey path from `t+1` on, hence next year's `w_trend` | `lambda` in `startYear+1`; re-solves from `startYear+2` |
| `UpdateYear` of `endYear` | | `MacroPaths.csv` export, diagnostics summary | | | `endYear` |

### 4.4 The first years of a run

| Year | Labour market | Wage multiplier | DSGE (DSGE modes) | Ramsey feedback |
|---|---|---|---|---|
| `startYear` | none (`firstYearSched`) | `w_trend + w_cycle`; `w_trend` already nonzero | initialised, stepped on the scenario's shocks | none |
| `startYear+1` | yes | `w_trend + w_cycle` | stepped on the scenario's shocks | `lambda` and the hours base latched at year end, no re-solve |
| `startYear+2` | yes | `w_trend + w_cycle` | stepped on the scenario's shocks | first gap and first re-solve at year end, effective from `startYear+3` |
| later | yes | `w_trend + w_cycle` | the same step | yearly |

`w_cycle` is zero in every year without scenario shocks.

### 4.5 What does not cross

- `pi_cycle`, `r*` and the planner's output, consumption, investment and capital are computed and written to
  `MacroPaths.csv` but reach no agent (`SimPathsModel.getMm_inflationDeviation` has no caller).
- SimPaths' incomes, taxes, benefits, consumption and wealth never reach the macro side. The only micro
  quantities the planner sees are the positive-hours headcount and mean hours; the demographic counts
  (population, working-age population, at-risk pool), potential wages (`wage_realized`) and the
  labour-category shares (`cat_*`) are recorded only.
- Nothing from SimPaths reaches the DSGE, at any point of the year. Nothing crosses in the Ramsey direction at
  `DSGEMacroUpdate` except the already-solved path.

### 4.6 Timing facts that are easy to get wrong

- **The wage of year `t` is one period stale** with respect to the Ramsey feedback: it comes from the path solved
  at the end of `t-1`, and year `t`'s realized labour changes the wage from `t+1`. This is the extended-path
  convention, not a defect (§8.1).
- **Union matching sees last year's multiplier.** `Person.Processes.UpdatePotentialHourlyEarnings` runs before
  `DSGEMacroUpdate` and applies `dw(t-1)`; `DSGEMacroUpdate` re-runs the wage equation with `dw(t)`
  before the labour market. The random component is the same in both calls (its draw changes at most once a year,
  in `Person.update`), and of the wage covariates (in the PL specification: age, education, health, region,
  part-time, the `wage_growth` index) union matching and fertility change only one: a newly matched woman moves
  to her partner's region (`UnionMatching.localMatch`). For everyone else only the multiplier differs between
  the two calls.
- **One labour count per year.** The planner's feedback takes `L_sim(t)` and `h(t)` at `MacroEndYearCapture`,
  after this year's labour market, mortality and population alignment. The level indices written at
  `DSGEMacroUpdate` come from the population before the labour market and are overwritten at the year end; no
  macro quantity is computed from them.
- **The first feedback moves the wage in `startYear+3`** (§4.4): `startYear+1` latches `lambda` and the hours
  base, `startYear+2` has the first re-solve, which the wage of `startYear+3` is the first to use.
- **A missing feedback stops the run.** If the end-of-year feedback of `t-1` did not run (`t-1 > startYear`),
  `updateEquilibrium` throws before advancing the planner's trend into `t`, naming `MacroEndYearCapture`.
- **`n_ramsey` changes meaning under feedback**: after the first re-solve it tracks the fed-back path, not the
  projection (§8.1).

---

## 5. Shock Conventions (Critical)

Dynare-exported policy matrices (`Bs`, `D`) contain raw `ghu` derivatives.  
Therefore input handling must be:

- Every scenario shock (`eps_a`, `eps_r`, `eps_d`, `eps_p`, `eps_L`, `eps_sep`, `eps_h`, `eps_inv`): an
  innovation in sigma-units, converted to a raw innovation by multiplying by sigma. SimPaths sends none.
- The labour force and hours follow their own AR(1) at the calibrated persistence the bundle carries
  (`shock_params.csv`; Poland 0.95 and 0.80 per quarter), like every other exogenous process, so a
  labour-force shock repeated every quarter accumulates and can trip `mm_dsgeMaxStateDeviation`.
- `model_info.json` declares the units (`deviation_scale = 100`); Java refuses a bundle that does not, with a
  message to re-export (contract 1).

Formula for every shock:

`rawInnovation_j = shockInSigmaUnits_j * sigma_j`

This matches Dynare:

`IRF(y, eps_j, t=0) = ghu(y, eps_j) * sigma_j`

---

## 6. Configuration Surface

### 6.1 Macro switches

All under `model_args`:

- `mm_macroModel` (`off | ramsey | dsge | ramsey_dsge`, default `off`; which layers run. `dsge`
  alone is diagnostic: the cycle is estimated on Ramsey-detrended observables, so without the
  trend layer it deviates around SimPaths' own wage trend instead)
- `mm_useMacroPathRecorder` (boolean, default `true`; records path regardless of `mm_macroModel`)
- `mm_dsgeShockScenario` (string; ignored unless `mm_macroModel` runs the DSGE)
- `mm_ramseyScenario` (string; ignored unless `mm_macroModel` runs the Ramsey layer)
- `mm_dsgeMaxStateDeviation` (double, default `10.0`; DSGE stability bound in log-deviation units)
- `mm_macroLogging` (`off | state | verbose`, default `off`; `verbose` implies `state`)
- `mm_ramseyFeedbackPersistence` (double, default `1.0`, range `[0,1]`; the planner's belief about how
  persistent a realized employment or hours gap is — see §8.1. The feedback itself has no switch: it is always
  on with a Ramsey layer. Ignored without one)

Unquoted `off` is a YAML 1.1 boolean, so SnakeYAML delivers `Boolean.FALSE`; the enum parser
maps it to the disabled constant, which is why `mm_macroModel: off` works without quotes.

A key under `model_args` that is not a field of `SimPathsModel` prints a `NoSuchFieldException` stack trace and
is otherwise ignored; the run goes on. `MacroScenarioConfigTest` fails on any `mm_*` key in `config/` that is not
a field of `SimPathsModel`.

---

## 7. Labour Count and Level Anchors

### 7.1 One count per year

The macro side counts SimPaths' labour supply once a year, at `MacroEndYearCapture`, after the labour market,
mortality and population alignment (`LaborSupplyAggregator.computeRawAggregates`). That count, the positive-hours
headcount `L_sim(t)` and mean hours `h(t)`, is what the planner's feedback reads (§8.1) and what the year's
level columns in `MacroPaths.csv` keep. The in-year level columns written at `DSGEMacroUpdate` come from the
population before the labour market and are overwritten by the end-of-year values.

### 7.2 Anchor-year asymmetry: the level indices start to move at different points

The recorded level indices do **not** all share the same base, and they start to
move at different points in the first two years. This is by design, not a bug, and
consumers (plots, `compare_scenarios.do`) must account for it.

Cause — two latches at different schedule phases, and an end-of-year rewrite:

- **Demographic anchors** (`startTotalPop`, `startWAP`, `startAtRisk`) are latched
  *eagerly, at the start year's `DSGEMacroUpdate`* (`latchDemographicAnchors`), before
  that year's mortality and population alignment. They are labour-market-invariant
  head counts, so this is safe.
- **L/h anchors** (`startL`, `startH`) are deliberately latched *post-labour-market*,
  and only in the **second** simulation year (`latchStartYearAnchorsPostLabourMarket`),
  because `L_0`/`h_0` must use SimPaths' model-assigned hours, not the input-DB
  hours present before the first labour-market update. The same latch opens the gate
  of `participation_rate`, `workers_over_atRiskPool` and the category diagnostics
  (`LaborSupplyAggregator.isStartYearLatched`; `LevelDiagnosticsTest`). It must not be
  moved earlier.
- **End-of-year rewrite.** At `MacroEndYearCapture`, `updateLevelAggregatesPostAlignment`
  recomputes the year's level columns from the post-alignment population, against the
  same anchors (`demographicIndexUpdates`, shared with the in-year pass), so the value
  a row keeps is the end-of-year one.

Net effect for a 2023-start run (checked on two runs of 2026-10-20):

- `total_population`, `wap_level`, `atrisk_level` are anchored on the 2023 population
  before its mortality and alignment, so the **2023** row already moves: it shows what
  2023's mortality and alignment did.
- `l_level`, `h_level` are anchored after the **2024** labour market. The 2023 row has
  no value (0); the 2024 row shows what 2024's mortality and alignment did to the
  post-labour-market count; from 2025 on a row is the change since the 2024 labour
  market.
- `n_ramsey` is the planner's annual-average labour input against the handoff quarter
  (`advanceRamseyTrend`), so it is nonzero from 2023 (§8.1 for its meaning under
  feedback).

Implications:

- The "% from first year" axis means a *different base* for the groups; do not read
  the 2023 `l_level = 0`, or the 2024 value, as "labour supply did not respond": the
  first is a placeholder before L's anchor exists, the second a within-year
  composition effect.
- In `compare_scenarios.do` the macro-impact panel is a scenario−baseline
  *difference*, so the lag cancels provided both arms use identical code and
  seed (the standard requirement); the asymmetry only matters for level plots.
- If the latch ever moves, the base of `l_level`/`h_level` and the first year of
  `participation_rate` move in every run; `compare_scenarios.do` anchors on the first
  non-zero value and must be checked with it.

---

## 8. Trend Handling

- Preferred path: Ramsey trend via `mm_macroModel: ramsey`
- If `growth_params_*.json` contains `terminal_growth.enabled=true`, annual TFP growth in the forward Ramsey
	baseline converges from the calibrated in-sample rate (`tfp_growth_annual`) toward the long-run anchor
	(`g_A_terminal_annual`) at the quarterly persistence `rho_quarterly`.
- The terminal balanced-growth-path target in Java is computed from the terminal TFP growth anchor, as in the
	MATLAB export that generates the Ramsey inputs.
- Without a Ramsey layer (`mm_macroModel` `off` or `dsge`) the trend wage is `0`. A requested Ramsey layer that
	fails to initialise stops the run (`MacroModelManager.requireRamseyTrendSolved`).
- The trend is not strictly top-down: with a Ramsey layer, realized labour supply always feeds back into the
	yearly re-solve (§8.1)

### 8.1 Recursive trend feedback (extended path)

Whenever a Ramsey layer runs, SimPaths' realized labour supply, employment and hours, is fed back into the
Ramsey trend once per simulated year, using the standard extended-path convention (re-solve the
remaining horizon from the predetermined capital state each time new information arrives). There is no
switch for it.

**Yearly mechanism.** At `MacroEndYearCapture` — after mortality and population alignment, the
only point in the year where employment is final — realized labour force
`L_sim,t` is aggregated by `LaborSupplyAggregator.computeRawAggregates`. In the first year with a
post-labour-market observation (`startYear + 1`; the labour market does not run in `startYear`), a
level factor `lambda = meanN_assumed / L_sim` is latched once and no re-solve happens that year
(`delta ≡ 1` by construction) — this mirrors the `startL` latch timing described in §7.2. In every
later year, `delta_t = lambda * L_sim,t / meanN_assumed(t)`, where `meanN_assumed(t)` is always
read from the *original* projection retained at startup, not from any previously revised path —
this is what prevents re-solve errors from compounding across years. The remaining-horizon labour
path is then rebuilt from the original projection with quarter `j` scaled by
`1 + (delta_t - 1) * rho^floor(j/4)` (`rho = mm_ramseyFeedbackPersistence`), and the model is
re-solved from `K` and `A` at the start of year `t` (predetermined, never revised), re-anchored to
the same scenario and TFP convergence phase so the terminal balanced-growth-path target keeps
converging instead of restarting. One `stepYear()` then consumes the realized year, leaving the
solved path positioned at `t + 1`.

**One-period-stale wages.** The wage SimPaths used for year `t` was set by `advanceRamseyTrend()`
*before* this feedback step runs — it reflects the path as solved at the end of year `t-1`, not
year `t`'s own realized labour. Only `advanceRamseyTrend()` for year `t+1` steps into the revised
path. This one-period lag is the standard extended-path convention, not a defect to be closed.

**A failed solve is fatal.** If the Ramsey trend fails to solve, the run stops rather than continuing without
the feedback. The feedback is the only channel through which SimPaths' labour reaches the planner.

**A missing feedback is fatal.** `applyRamseyRecursiveFeedback` records the year it ran, and
`updateEquilibrium` refuses to advance the trend into year `t` when the feedback of `t-1` did not run
(`t-1 > startYear`), with a message naming `MacroEndYearCapture`. Advancing on a path that ignores a year of
SimPaths' labour supply would be a silent fallback. The guard is in the schedule-driven path, not in
`advanceRamseyTrend`, which tests step on their own. Degenerate inputs (a non-positive realised `L`, an
exhausted projection horizon, unusable hours) are logged as warnings and that year's re-solve is skipped; the
year still counts as fed back, because the check tests that the hook ran, not that it re-solved.

**The DSGE takes no part.** With a DSGE mode the cycle layer runs on the scenario's shocks alone and takes no
input from SimPaths (§3.1), so the planner's feedback is the only channel from SimPaths' labour supply to the
macro side. Consequence: with no scenario shocks, a Ramsey+DSGE run has the Ramsey-only wage index year by year
on the same seed, and its cycle wage is zero (`DsgeOneWayTest`; end to end, `config/ramsey-feedback-persistent.yml`
against `config/ramsey-dsge-feedback-persistent.yml`).

**Hours feedback.** Realized mean hours per worker are fed back alongside the headcount, with the same
persistence `rho`. Heads-only feedback would show the planner `delta_n` while the labour input is `N*h`
(`RamseyTrendModel`), getting the capital-dilution response wrong whenever the two margins move in opposite
directions; an x% hours gap dilutes capital exactly as an x% headcount gap does. Diagnostic column:
`h_realized_gap`.

**The two gaps use different references, and this is the subtle part.** `delta_n` is measured
against the planner's projected `N`, which is legitimate only because `extDemographic` is
overwritten at startup with the same population projection SimPaths aligns to — realized and
projected employment are then comparable and `delta_n` isolates genuine labour-market surprises.
No equivalent reconciliation exists for hours. `projH` (read from `hours_projection_*.csv`) is a
log-linear trend fitted to observed
hours (`hours_trend_method` in the growth params; for PL, -0.203%/yr, and the `h` column of
`growth_model_reference_poland.csv` is a pure exponential at that one rate, not a data series).
SimPaths models no hours trend at all, so measuring `delta_h` against `projH` would compare two
different modelling assumptions: by 2060 the planner assumes hours 7.0% below their 2024 level
while SimPaths delivers 1.3% above, i.e. a spurious `delta_h` near +9% that grows monotonically
instead of mean-reverting, worth roughly -2.5% of the trend wage at `rho=1`.

`delta_h` is therefore measured against **SimPaths' own latch-year hours** (`ramseyFeedbackHoursBase`),
not against `projH`: the planner keeps its fitted trend as a maintained assumption and SimPaths
feeds back only its own hours movements on top of it. Flat SimPaths hours give `delta_h = 1` and no
revision, which is correct, because SimPaths carries no information about the hours trend. Pinned by
`RamseyRecursiveFeedbackTest.testFlatSimPathsHoursLeaveTheDecliningProjectionAlone`, which also
asserts the fixture's hours projection really does decline so the test cannot go vacuous. Revisit
the reference if SimPaths ever gains an exogenous hours projection, e.g. an hours alignment.

**Handoff contract.** MATLAB writes `hours_projection_{COUNTRY}.csv` (single writer
`internal/write_hours_projection.m`, called by both export paths, listed in `verify_export_bundle.m`) and
`MacroModelManager.loadProjectedHours` reads it, failing loudly if it is absent. There is no local fallback:
one would let the two sides use different trailing-growth windows unnoticed. The terminal tail is built
independently on both sides; its windows are pinned by `RamseyTrendModel.TAIL_DEMOGRAPHIC_WINDOW` /
`TAIL_HOURS_WINDOW` and the tripwire in `MacroModelManagerTest`.

**Hours terminal tail.** The 600-quarter terminal tail extends hours with their trailing growth rate tapered
geometrically to zero (half-life 20 quarters), on both sides: `RamseyTrendModel.extendWithTaperedGrowth` and
`apply_smooth_terminal_demographic_closure(..., 1)` in `ramsey_trend.m`. The taper is needed because hours per
worker are constant on a balanced growth path (terminal condition `K/(kyBgp*Y) = 1`); left undamped over the
150-year tail, the fitted -0.2%/yr decline would take hours to about 69% of their handoff level. The tail is a
terminal-condition device, not a forecast; its effect on the trend wage over 2023-2060 is under 0.003%.

**Persistence (`rho`) semantics.** `rho` is the assumed persistence of a realized employment or hours
surprise, the same for both, applied as a per-year geometric decay on the tail: `rho=1` treats the whole gap as
permanent (a random-walk belief in the surprise), `rho=0` replaces only the realized year and
leaves every future quarter at the originally assumed path. **`rho=0` is a near-zero-feedback
setting by construction, not a "weak feedback" setting**: with only the current year replaced, the
planner still expects labour to revert next period, so the announced wage for `t+1` is essentially
the marginal product of labour at *assumed* labour, and a persistent labour-supply shift never
produces the capital-dilution wage response the channel exists to deliver. On the Poland bundle (measured
2026-08-17), a +2% persistent employment surprise revises the next year's trend wage by about **-0.56%**
under `rho=1` (capital dilution, of the order `alpha * 2%`) versus about **-0.01%** under `rho=0`, i.e. `rho=0`
delivers essentially no feedback; `RamseyRecursiveFeedbackTest` checks the bands (below -0.10% and above -1.20%
under `rho=1`, within ±0.10% under `rho=0`). This is why the default is `rho=1.0`; `rho=0` is settable for
comparison runs (no shipped config uses it), not as a milder version of the feature.

**Diagnostics.** Besides `h_realized_gap` (above), two columns are appended to all four quarterly rows of year `t` in
`MacroPaths.csv`:
`n_realized_gap` — `(delta_t - 1) * 100`, the realized gap in percent of SimPaths' workers with positive hours
against the planner's assumed employees path (so it includes shifts in the employee share, see "Employees versus
all workers" below) — and
`w_trend_revision` — the percent change in the year-`t+1` trend wage caused by the re-solve. Both
default to `0` for any year with no re-solve: the latch year (`n_realized_gap` is legitimately `0`
there, since `delta ≡ 1`; `w_trend_revision` is `0` because no re-solve happens to produce a
revision) and every year of a run without a Ramsey layer.

**`n_ramsey` changes meaning under feedback.** `advanceRamseyTrend` derives the `n_ramsey` column
(§7.2) from the *currently solved* trend's `ts.labor()`, so once a re-solve has happened,
`n_ramsey` tracks the fed-back, revised labour path — not the population-projection-based path it
represents before the first re-solve. The measure of how far a run departed from the assumed path is
`n_realized_gap`, not `n_ramsey`.

**Employees versus all workers: what `lambda` holds fixed.** The planner's `N` is employees, the
national-accounts headcount behind its wage and hours: compensation and hours exist per employee only, because
the self-employed earn mixed income, with no wage or hours attached (`make_datasets.do`, block "WHY EXCLUDE
SELF-EMPLOYED?"; the employees-concept note on `N_trend` in `ramsey_trend.m`). `L_sim` counts everyone aged 15-64
at risk of work with positive hours, employees and self-employed alike (`LaborSupplyAggregator.computeRawAggregates`):
SimPaths' labour-supply model includes the self-employed (`Les_c4.EmployedOrSelfEmployed`), its wage equation is
estimated on pooled employee and self-employment income, and it carries no person-level status in employment.
The level factor `lambda = meanN_assumed / L_sim`, latched in `startYear + 1`, is therefore a units conversion
(the population scale) times the employee share of workers in that year, and `N_planner(t) = lambda * L_sim(t)`
holds that share fixed for the rest of the run. This is accepted as a documented assumption, the same one the
DSGE calibration makes (contract 16 in `Integration_Contracts.md`): any shift in the employee share reaches the
planner as a labour-market surprise, so `n_realized_gap` includes composition shifts between employees and
self-employed, and the planner prices them like any other employment gap. The hours margin carries the same
mismatch, since `delta_h` compares the hours of all workers with a planner path in hours per employee; because it
is measured against SimPaths' own latch-year hours, only differences in how the two groups' hours move enter. The
size is bounded by how far the employee share moves over the horizon: a shift of 2 percentage points is of the
order of a 2% employment surprise. In Poland self-employment is about a fifth of employment, with a declining
agricultural component, so the shift is structural and one-directional, but small. Removing it would need either
a projected employee-share path in the bundle or a status-in-employment variable in SimPaths; neither is
implemented.

**What the feedback assumes about the labour market.** With the feedback on, the coupled model's labour market
clears through the wage, with a one-period lag. SimPaths has no unemployment state (`Les_c4` knows `NotEmployed`,
`Student` and `Retired`, nothing finer) and no rationing: whoever chooses positive hours works them and earns the wage, and
incomes, taxes, benefits and poverty are computed on that basis. The planner's realized labour input is
`lambda * L_sim(t)` exactly, and the trend wage is the marginal product of labour at that quantity, revised one
year later through capital dilution. A change in SimPaths' labour supply is therefore an employment change,
dampened by the trend-wage response it triggers through SimPaths' own wage elasticity: labour demand shows up as
that wage response, not as unemployment. This is accepted as the design of the trend layer and is the standard
assumption for one; long-run unemployment is set by institutions, which TFP and demographics do not pin down.
Three consequences:

- The *level* of `L_sim` reflects the unemployment of the estimation period, because the labour-supply model is
  estimated on observed hours and its zero-hours category contains the sample-period unemployed; `lambda` holds
  that level relationship fixed. *Changes* in `L_sim` are supply responses.
- There is no NAIRU wedge: realized labour supply is fed back directly, without an exogenous
  `N = L (1 - u*)` adjustment. The NAWRU series `ramsey_trend.m` builds is kept for diagnostics only.
- Unemployment exists only in the DSGE layer (search and matching, `U = L - N`), where it is cyclical and
  mean-reverting, and driven by the scenario's shocks alone.

The diagnostics follow the code's naming: "employment" in `n_realized_gap` and in the log lines means SimPaths'
workers with positive hours, which in this market is employment.

`ramseyBaseline` (the quarter-0 anchor used for all `advanceRamseyTrend` percentage trends) is never re-latched
by this channel.

---

## 9. Outputs and Diagnostics

### 9.1 Path export

The macro paths are exported to:

- `output/{run_id}/csv/MacroPaths.csv`

### 9.2 Logging

- Year-level macro logging: `mm_macroLogging: state`
- Additional diagnostics: `mm_macroLogging: verbose`

---

## 10. Code Map

### 10.1 Main classes

`src/main/java/simpaths/model/macro/`

- `MacroModelManager.java`
- `DSGEModel.java`
- `DSGEState.java`
- `LaborSupplyAggregator.java`
- `MacroPathRecorder.java`
- `DSGEShockScenario.java`
- `RamseyTrendModel.java`
- `RamseyScenario.java`
- `MacroCsv.java`, `MacroJson.java` (readers for the bundle's CSV and JSON files)

### 10.2 Tests

`src/test/java/simpaths/model/macro/`

`BundleProvenanceTest` (contract 18), `CommittedBundlesTest` (replays every committed bundle's IRFs and Ramsey
forward solve), `DSGEBundleContractTest`, `DSGEIntegrationTest`, `DSGEIrfParityTest`,
`DSGEModelTest`, `DSGEShockScenarioTest`, `DSGEStateTest`, `DsgeOneWayTest` (contract 38),
`LaborSupplyAggregatorTest`, `LevelDiagnosticsTest`, `MacroJsonTest`, `MacroModelManagerTest`,
`MacroPathRecorderTest`, `MacroScenarioConfigTest`, `MacroSwitchContractTest`, `RamseyRecursiveFeedbackTest`,
`RamseyScenarioTest`, `RamseyTrendModelTest` (MATLAB parity against `src/test/resources/macro/exogenous/` at
`RELATIVE_TOLERANCE = 1e-9`),
`ScenarioLoaderParityTest`. `MacroTestSupport` holds the helpers that drive a manager through the schedule.

---

## 11. References

- The macro model itself: `Technical_Documentation_Macromodel.md` (this folder)
- Contracts between the MATLAB and Java sides: `Integration_Contracts.md` (this folder)
- Running the model and re-exporting a bundle: `../README.md`
- Rules for Java changes to the macro layer: the "Macro model" section of the root `CLAUDE.md`
