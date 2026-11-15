package com.bloodnet.shared;

/** ABO + Rh group. Stored as the enum name (e.g. O_NEG); {@link #label()} is the human form (O-). */
public enum BloodGroup {
    O_NEG("O-"), O_POS("O+"), A_NEG("A-"), A_POS("A+"),
    B_NEG("B-"), B_POS("B+"), AB_NEG("AB-"), AB_POS("AB+");

    private final String label;

    BloodGroup(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
