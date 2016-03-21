package com.github.srstclair.photoorganizer;

import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

import com.github.srstclair.photoorganizer.PhotoOrganizer;

public class PlacenamesTest {

    @Test
    public void testPlacenames() throws IOException {
        assertTrue(PhotoOrganizer.PLACENAMES.available() > 0);
    }
}
