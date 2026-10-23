package com.example.entity;

public class User extends BaseEntity {
    private String userName;
    private Address address;

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }
}
