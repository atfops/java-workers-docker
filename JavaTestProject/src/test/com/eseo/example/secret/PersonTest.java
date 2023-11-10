package com.eseo.example.secret;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PersonTest {

    @Test
    public void testToString() {
        Person person = new Person();
        person.setName("John");
        person.setAge(30);

        String expected = "Person{name='John', age=31}";
        String actual = person.toString();

        assertEquals(expected, actual);
    }
}
