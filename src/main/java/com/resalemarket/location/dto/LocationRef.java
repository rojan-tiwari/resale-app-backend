package com.resalemarket.location.dto;

/** The ids of a place: a province, optionally narrowed to a district and a municipality. Used as a key to look names up in bulk. */
public record LocationRef(Integer provinceId, Integer districtId, Integer municipalityId) {
}
