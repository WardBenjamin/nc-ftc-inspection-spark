
//this code initializes any datepickers on the page
$( function() {
    $( "#datepicker" ).datepicker();
  } );
//old versions of safari don't have endsWith
String.prototype.endsWith = function(suffix) {
    return this.indexOf(suffix, this.length - suffix.length) !== -1;
};