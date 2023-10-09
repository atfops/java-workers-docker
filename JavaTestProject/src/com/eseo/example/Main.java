package com.eseo.example;

import org.jeasy.random.api.Randomizer;

public class Main {

    static class Person {
        private String name;
        private int age;

        // getters and setters ...

        @Override
        public String toString() {
            return "Person{name='" + name + '\'' + ", age=" + age + '}';
        }
    }

    public static void main(String[] args) {
        AgeRandomizer random = new AgeRandomizer();

        int[] test = new int[10];
        for (int i = 0; i < 10; i++) {
            test[i] = random.getRandomValue();
        }

        for (int i = 0; i < 10; i++) {
            System.out.println(test[i]);
        }

        System.out.println("Hello, World! Meet " + random.getRandomValue());
    }

    // Custom randomizer for age between 20 and 50
    static class AgeRandomizer implements Randomizer<Integer> {
        @Override
        public Integer getRandomValue() {
            return 20 + (int) (Math.random() * 31);  // 20 <= age < 51
        }
    }
}







