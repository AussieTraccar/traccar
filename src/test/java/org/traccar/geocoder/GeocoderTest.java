package org.traccar.geocoder;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GeocoderTest {

    static {
        Locale.setDefault(Locale.US);
    }

    private final Client client = ClientBuilder.newClient();

    @Disabled
    @Test
    public void testGoogle() {
        Geocoder geocoder = new GoogleGeocoder(client, null, null, null, 0, new AddressFormat());
        String address = geocoder.getAddress(31.776797, 35.211489, null);
        assertEquals("1 Ibn Shaprut St, Jerusalem, Jerusalem District, IL", address);
    }

    @Disabled
    @Test
    public void testNominatim() {
        Geocoder geocoder = new NominatimGeocoder(client, null, null, null, 0, new AddressFormat());
        String address = geocoder.getAddress(40.7337807, -73.9974401, null);
        assertEquals("35 West 9th Street, NYC, New York, US", address);
    }

    @Disabled
    @Test
    public void testMapbox() {
        Geocoder geocoder = new MapboxGeocoder(client, "", 0, new AddressFormat("%f"));
        String address = geocoder.getAddress(40.733, -73.989, null);
        assertEquals("120 East 13th Street, New York, New York 10003, United States", address);
    }

    @Disabled
    @Test
    public void testMapTiler() {
        Geocoder geocoder = new MapTilerGeocoder(client, "", 0, new AddressFormat());
        String address = geocoder.getAddress(40.733, -73.989, null);
        assertEquals("East 13th Street, New York City, New York, United States", address);
    }

    @Disabled
    @Test
    public void testGeocodeJSON() {
        Geocoder geocoder = new GeocodeJsonGeocoder(client, null, null, null, 0, new AddressFormat());
        String address = geocoder.getAddress(40.7337807, -73.9974401, null);
        assertEquals("35 West 9th Street, New York, New York, US", address);
    }
}
