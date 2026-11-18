package simpaths.data;


import microsim.statistics.regression.RegressionType;

public enum RegressionName {

    ChildcareC1b(RegressionType.Linear),

    EducationE1a(RegressionType.Probit),
    EducationE1b(RegressionType.Probit),
    EducationE2a(RegressionType.GenOrderedLogit),

    FertilityF1(RegressionType.Probit),

    PartnershipU1(RegressionType.Probit),
    PartnershipU2(RegressionType.Probit),


    HealthH1(RegressionType.GenOrderedLogit),
    HealthH2(RegressionType.Probit),

    HealthHM1Level(RegressionType.Linear),
    HealthHM2LevelMales(RegressionType.Linear),
    HealthHM2LevelFemales(RegressionType.Linear),
    HealthHM1Case(RegressionType.Logit),
    HealthHM2CaseMales(RegressionType.Logit),
    HealthHM2CaseFemales(RegressionType.Logit),

    RMSE(RegressionType.Linear),

    UnemploymentU1a(RegressionType.Probit),
    UnemploymentU1b(RegressionType.Probit),
    UnemploymentU1c(RegressionType.Probit),
    UnemploymentU1d(RegressionType.Probit),

    WagesMales(RegressionType.Linear),
    W1mb(RegressionType.Linear),
    W1ma(RegressionType.Linear),
    WagesFemales(RegressionType.Linear),
    W1fb(RegressionType.Linear),
    W1fa(RegressionType.Linear);

    private final RegressionType type;

    RegressionName(RegressionType tt) {
        type = tt;
    }

    public RegressionType getType() {
        return type;
    }
}
