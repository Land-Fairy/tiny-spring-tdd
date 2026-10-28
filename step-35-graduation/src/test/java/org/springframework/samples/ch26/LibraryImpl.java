package org.springframework.samples.ch26;

public class LibraryImpl implements Library {

	private int booked;

	@Override
	public void book(String seat) {
		this.booked++;
	}

	@Override
	public void renew(String seat) {
		this.booked++;
	}

	@Override
	public int count() {
		return this.booked;
	}

}
