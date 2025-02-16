package freenet.client.async;

import static freenet.client.async.USKDateHint.Type.DAY;
import static freenet.client.async.USKDateHint.Type.MONTH;
import static freenet.client.async.USKDateHint.Type.WEEK;
import static freenet.client.async.USKDateHint.Type.YEAR;
import static org.junit.Assert.assertEquals;

import java.time.LocalDate;

import org.junit.Test;

public class USKDateHintTest {

    @Test
    public void getYear() {
        USKDateHint hint = new USKDateHint(LocalDate.parse("2023-06-01"));
        assertEquals("2023", hint.get(YEAR));
    }

    @Test
    public void getMonth() {
        USKDateHint hint = new USKDateHint(LocalDate.parse("2023-06-01"));
        assertEquals("2023-5", hint.get(MONTH));
    }

    @Test
    public void getDay() {
        USKDateHint hint = new USKDateHint(LocalDate.parse("2023-06-01"));
        assertEquals("2023-5-1", hint.get(DAY));
    }

    @Test
    public void getWeek() {
        USKDateHint hintStartOfYear = new USKDateHint(LocalDate.parse("2023-01-01"));
        USKDateHint hintEndOfYear = new USKDateHint(LocalDate.parse("2023-12-31"));
        assertEquals("2023-WEEK-1", hintStartOfYear.get(WEEK));
        assertEquals("2024-WEEK-1", hintEndOfYear.get(WEEK));
    }

    @Test
    public void getData() {
        USKDateHint hint = new USKDateHint(LocalDate.parse("2023-06-01"));
        assertEquals("HINT\n12345\n2023-5-1\n", hint.getData(12345));
    }

}
