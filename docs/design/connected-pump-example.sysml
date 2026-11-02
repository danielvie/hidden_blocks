package PumpSystem {
    public import ScalarValues::*;

    item def Water;
    port def FluidPort {
        out item water : Water;
    }

    part def Pump {
        attribute flowRate : Real default = 10;
        port outlet : FluidPort;
    }

    part def VariableSpeedPump :> Pump {
        attribute speed : Real = 1800;
    }

    part def Tank {
        attribute capacity : Real = 250;
        port inlet : ~FluidPort;
    }

    part system {
        part pump : VariableSpeedPump {
            attribute flowRate :>> Pump::flowRate = 10;
        }
        part tank : Tank;
        connect pump.outlet to tank.inlet;
    }
}
