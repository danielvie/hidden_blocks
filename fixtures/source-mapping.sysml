package Mapping {
    public import ScalarValues::*;
    // Unicode before all ranges: music 𝄞
    part def Pump {
        attribute flowRate : Real = 10;
        attribute precision : Real = 0.12345678901234567890123456789;
        attribute count : Integer = 2147483647;
        attribute enabled : Boolean = true;
        attribute label : String = "line\n\"quoted\"";
        attribute computed : Real = 1 + 2;
        derived attribute derivedValue : Real = 4;
    }
    part def Backup {
        attribute flowRate : Real = 7;
    }
    part pump : Pump;
}
