package com.eseo.example;

import org.jeasy.random.api.Randomizer;

public class Main {

    public static void main(String[] args) {
        AgeRandomizer random = new AgeRandomizer();

        int[] test = new int[10];
        for (int i = 0; i < 10; i++) {
            test[i] = random.getRandomValue();
        }

        System.out.println("Hello World!");
        for (int i = 0; i < 10; i++) {
            System.out.println(i);
        }
    }

    // Custom randomizer for age between 20 and 50
    static class AgeRandomizer implements Randomizer<Integer> {
        @Override
        public Integer getRandomValue() {
            return 20 + (int) (Math.random() * 31);  // 20 <= age < 51
        }
    }
}







