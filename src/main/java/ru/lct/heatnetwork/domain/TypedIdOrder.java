package ru.lct.heatnetwork.domain;

import java.util.Comparator;

/** Stable ordering used when equal-distance spatial candidates need a deterministic winner. */
public final class TypedIdOrder implements Comparator<TypedId> {
    public static final TypedIdOrder INSTANCE = new TypedIdOrder();

    private TypedIdOrder() { }

    @Override
    public int compare(TypedId left, TypedId right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        if (left.isNumeric() != right.isNumeric()) return left.isNumeric() ? -1 : 1;
        return left.isNumeric() ? left.asNumber().compareTo(right.asNumber())
                : compareNatural(left.asString(), right.asString());
    }

    private static int compareNatural(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            char leftChar = left.charAt(leftIndex);
            char rightChar = right.charAt(rightIndex);
            if (Character.isDigit(leftChar) && Character.isDigit(rightChar)) {
                int leftEnd = digitEnd(left, leftIndex);
                int rightEnd = digitEnd(right, rightIndex);
                int leftSignificant = skipZeroes(left, leftIndex, leftEnd);
                int rightSignificant = skipZeroes(right, rightIndex, rightEnd);
                int leftDigits = leftEnd - leftSignificant;
                int rightDigits = rightEnd - rightSignificant;
                if (leftDigits != rightDigits) return Integer.compare(leftDigits, rightDigits);
                for (int offset = 0; offset < leftDigits; offset++) {
                    int digitComparison = Character.compare(left.charAt(leftSignificant + offset),
                            right.charAt(rightSignificant + offset));
                    if (digitComparison != 0) return digitComparison;
                }
                int runComparison = Integer.compare(leftEnd - leftIndex, rightEnd - rightIndex);
                if (runComparison != 0) return runComparison;
                leftIndex = leftEnd;
                rightIndex = rightEnd;
            } else {
                int characterComparison = Character.compare(leftChar, rightChar);
                if (characterComparison != 0) return characterComparison;
                leftIndex++;
                rightIndex++;
            }
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    private static int digitEnd(String value, int start) {
        int end = start;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        return end;
    }

    private static int skipZeroes(String value, int start, int end) {
        int significant = start;
        while (significant < end - 1 && value.charAt(significant) == '0') significant++;
        return significant;
    }
}
