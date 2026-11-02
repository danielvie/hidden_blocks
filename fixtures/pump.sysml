package PumpModel {
    public import ScalarValues::*;
    part def Pump {
        // Keep this comment when changing the flow rate.
        attribute flowRate : Real = 10;
    }
    part def Controller {
        attribute threshold : Real = 30;
    }
    part def Tank {
        attribute capacity : Real = 250;
    }
}
