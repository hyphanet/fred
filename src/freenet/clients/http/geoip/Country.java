package freenet.clients.http.geoip;



public class Country {

	private String name;
	private String code;

	private final static String[] COUNTRIES = { "localhost", "IntraNet",
			"Anonymous Proxy", "Satellite Provider", "Andorra",
			"United Arab Emirates", "Afghanistan", "Antigua and Barbuda",
			"Anguilla", "Albania", "Armenia", "Netherlands Antilles", "Angola",
			"Asia/Pacific Region", "Antarctica", "Argentina", "American Samoa",
			"Austria", "Australia", "Aruba", "Aland Islands", "Azerbaijan",
			"Bosnia and Herzegovina", "Barbados", "Bangladesh", "Belgium",
			"Burkina Faso", "Bulgaria", "Bahrain", "Burundi", "Benin",
			"Bermuda", "Brunei Darussalam", "Bolivia", "Brazil", "Bahamas",
			"Bhutan", "Bouvet Island", "Botswana", "Belarus", "Belize",
			"Canada", "Cocos (Keeling) Islands",
			"Congo, The Democratic Republic of the",
			"Central African Republic", "Congo", "Switzerland",
			"Cote D'Ivoire", "Cook Islands", "Chile", "Cameroon", "China",
			"Colombia", "Costa Rica", "Serbia and Montenegro", "Cuba",
			"Cape Verde", "Christmas Island", "Cyprus", "Czech Republic",
			"Germany", "Djibouti", "Denmark", "Dominica", "Dominican Republic",
			"Algeria", "Ecuador", "Estonia", "Egypt", "Western Sahara",
			"Eritrea", "Spain", "Ethiopia", "Europe", "Finland", "Fiji",
			"Falkland Islands (Malvinas)", "Micronesia, Federated States of",
			"Faroe Islands", "France", "France Metropolitan", "Gabon",
			"United Kingdom", "Grenada", "Georgia", "French Guiana",
			"Guernsey", "Ghana", "Gibraltar", "Greenland", "Gambia", "Guinea",
			"Guadeloupe", "Equatorial Guinea", "Greece",
			"South Georgia and the South Sandwich Islands", "Guatemala",
			"Guam", "Guinea-Bissau", "Guyana", "Hong Kong",
			"Heard Island and McDonald Islands", "Honduras", "Croatia",
			"Haiti", "Hungary", "Indonesia", "Ireland", "Israel",
			"Isle of Man", "India", "British Indian Ocean Territory", "Iraq",
			"Iran, Islamic Republic of", "Iceland", "Italy", "Jersey",
			"Jamaica", "Jordan", "Japan", "Kenya", "Kyrgyzstan", "Cambodia",
			"Kiribati", "Comoros", "Saint Kitts and Nevis",
			"Korea, Democratic People's Republic of", "Korea, Republic of",
			"Kuwait", "Cayman Islands", "Kazakhstan",
			"Lao People's Democratic Republic", "Lebanon", "Saint Lucia",
			"Liechtenstein", "Sri Lanka", "Liberia", "Lesotho", "Lithuania",
			"Luxembourg", "Latvia", "Libyan Arab Jamahiriya", "Morocco",
			"Monaco", "Moldova, Republic of", "Montenegro", "Saint Martin",
			"Madagascar", "Marshall Islands",
			"Macedonia, the Former Yugoslav Republic of", "Mali", "Myanmar",
			"Mongolia", "Macau", "Northern Mariana Islands", "Martinique",
			"Mauritania", "Montserrat", "Malta", "Mauritius", "Maldives",
			"Malawi", "Mexico", "Malaysia", "Mozambique", "Namibia",
			"New Caledonia", "Niger", "Norfolk Island", "Nigeria", "Nicaragua",
			"Netherlands", "Norway", "Nepal", "Nauru", "Niue", "New Zealand",
			"Oman", "Panama", "Peru", "French Polynesia", "Papua New Guinea",
			"Philippines", "Pakistan", "Poland", "Saint Pierre and Miquelon",
			"Pitcairn", "Puerto Rico", "Palestinian Territory, Occupied",
			"Portugal", "Palau", "Paraguay", "Qatar", "Reunion", "Romania",
			"Serbia", "Russian Federation", "Rwanda", "Saudi Arabia",
			"SolomonIslands", "Seychelles", "Sudan", "Sweden", "Singapore",
			"Saint Helena", "Slovenia", "Svalbard and Jan Mayen", "Slovakia",
			"Sierra Leone", "San Marino", "Senegal", "Somalia", "Suriname",
			"Sao Tome and Principe", "El Salvador", "Syrian Arab Republic",
			"Swaziland", "Turks and Caicos Islands", "Chad",
			"French Southern Territories", "Togo", "Thailand", "Tajikistan",
			"Tokelau", "Timor-Leste", "Turkmenistan", "Tunisia", "Tonga",
			"East Timor", "Turkey", "Trinidad and Tobago", "Tuvalu",
			"Taiwan, Province of China", "Tanzania, United Republic of",
			"Ukraine", "Uganda", "United Kingdom",
			"United States Minor Outlying Islands", "United States", "Uruguay",
			"Uzbekistan", "Holy See (Vatican City State)",
			"Saint Vincent and the Grenadines", "Venezuela",
			"Virgin Islands, British", "Virgin Islands, U.S.", "Vietnam",
			"Vanuatu", "Wallis and Futuna", "Samoa", "Yemen", "Mayotte",
			"Serbia and Montenegro (Formally Yugoslavia)", "South Africa",
			"Zambia", "Zimbabwe" };

	private final static String[] CODES = { "L0", "I0", "A1", "A2", "AD", "AE",
			"AF", "AG", "AI", "AL", "AM", "AN", "AO", "AP", "AQ", "AR", "AS",
			"AT", "AU", "AW", "AX", "AZ", "BA", "BB", "BD", "BE", "BF", "BG",
			"BH", "BI", "BJ", "BM", "BN", "BO", "BR", "BS", "BT", "BV", "BW",
			"BY", "BZ", "CA", "CC", "CD", "CF", "CG", "CH", "CI", "CK", "CL",
			"CM", "CN", "CO", "CR", "CS", "CU", "CV", "CX", "CY", "CZ", "DE",
			"DJ", "DK", "DM", "DO", "DZ", "EC", "EE", "EG", "EH", "ER", "ES",
			"ET", "EU", "FI", "FJ", "FK", "FM", "FO", "FR", "FX", "GA", "GB",
			"GD", "GE", "GF", "GG", "GH", "GI", "GL", "GM", "GN", "GP", "GQ",
			"GR", "GS", "GT", "GU", "GW", "GY", "HK", "HM", "HN", "HR", "HT",
			"HU", "ID", "IE", "IL", "IM", "IN", "IO", "IQ", "IR", "IS", "IT",
			"JE", "JM", "JO", "JP", "KE", "KG", "KH", "KI", "KM", "KN", "KP",
			"KR", "KW", "KY", "KZ", "LA", "LB", "LC", "LI", "LK", "LR", "LS",
			"LT", "LU", "LV", "LY", "MA", "MC", "MD", "ME", "MF", "MG", "MH",
			"MK", "ML", "MM", "MN", "MO", "MP", "MQ", "MR", "MS", "MT", "MU",
			"MV", "MW", "MX", "MY", "MZ", "NA", "NC", "NE", "NF", "NG", "NI",
			"NL", "NO", "NP", "NR", "NU", "NZ", "OM", "PA", "PE", "PF", "PG",
			"PH", "PK", "PL", "PM", "PN", "PR", "PS", "PT", "PW", "PY", "QA",
			"RE", "RO", "RS", "RU", "RW", "SA", "SB", "SC", "SD", "SE", "SG",
			"SH", "SI", "SJ", "SK", "SL", "SM", "SN", "SO", "SR", "ST", "SV",
			"SY", "SZ", "TC", "TD", "TF", "TG", "TH", "TJ", "TK", "TL", "TM",
			"TN", "TO", "TP", "TR", "TT", "TV", "TW", "TZ", "UA", "UG", "UK",
			"UM", "US", "UY", "UZ", "VA", "VC", "VE", "VG", "VI", "VN", "VU",
			"WF", "WS", "YE", "YT", "YU", "ZA", "ZM", "ZW" };

	public Country(String code) {
		this.code = code;
		this.name = getName(code);
	}
	
	public static String getCode(String name) {
		int index = getIndex(COUNTRIES,name);
		return index==-1?"NA":CODES[index];
	}
	
	public static String getName(String code) {
		int index = getIndex(CODES, code);
		return index==-1?"NA":COUNTRIES[index];
	}
	
	private static int getIndex(String[] arr,String el) {
		for(int i=0;i<arr.length;i++) {
			if(arr[i].equals(el))return i;
		}
		return -1;
	}

	public String getName() {
		return name;
	}

	public String getCode() {
		return code;
	}

	public static String[] getCountries() {
		return COUNTRIES;
	}

	public static String[] getCodes() {
		return CODES;
	}

	@Override
	public String toString() {
		return this.code+":"+this.name;
	}
	
}
