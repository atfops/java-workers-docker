package com.eseo.example.secret;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SiuTest {

    @Test
    public void testGetAge() {
        Siu siu = new Siu();
        siu.age = 25;
        assertEquals(25, siu.getAge());
    }

    @Test
    public void testGetName() {
        Siu siu = new Siu();
        siu.name = "John";
        assertEquals("John", siu.getName());
    }

    @Test
    public void testToString() {
        Siu siu = new Siu();
        siu.age = 25;
        siu.name = "John";
        assertEquals("John25", siu.toString());
    }
}