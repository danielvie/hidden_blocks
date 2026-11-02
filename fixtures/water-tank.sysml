package WaterTankSystem {
    public import ScalarValues::*;

    item def PumpCommand;
    item def Water;
    item def LevelReading;

    port def CommandPort {
        out item command : PumpCommand;
    }
    port def WaterPort {
        out item water : Water;
    }
    port def LevelPort {
        out item level : LevelReading;
    }

    part def Controller {
        port levelIn : ~LevelPort;
        port pumpCommandOut : CommandPort;
    }
    part def Pump {
        port commandIn : ~CommandPort;
        port waterOut : WaterPort;
    }
    part def Tank {
        port waterIn : ~WaterPort;
        port levelOut : LevelPort;
    }

    part system {
        part controller : Controller;
        part pump : Pump {
            attribute flowRate : Real = 10;
        }
        part tank : Tank;

        connect controller.pumpCommandOut to pump.commandIn;
        connect pump.waterOut to tank.waterIn;
        connect tank.levelOut to controller.levelIn;
    }
}
