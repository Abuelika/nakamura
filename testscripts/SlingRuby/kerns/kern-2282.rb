#!/usr/bin/env ruby


require 'nakamura/test'
require 'test/unit.rb'
include SlingUsers


class TC_Kern2282 < Test::Unit::TestCase
  include SlingTest

  def test_migrate_pseudo_group
    m = Time.now.to_nsec
    
    group = Group.new("g-test-#{m}")
    @s.switch_user(User.admin_user())
    res = @s.execute_post(@s.url_for("#{$GROUP_URI}"), {
      ":name" => group.name,
      "_charset_" => "UTF-8"
    })
    
    @s.execute_post(@s.url_for("/system/userManager/group/#{group.name}.update.html"), {
      "sakai:pseudoGroup" => "true"
    })
    
    res = @s.execute_get(@s.url_for("/system/userManager/group/#{group.name}.tidy.json"))
    json = JSON.parse(res.body)
    assert_equal("true", json['properties']['sakai:pseudoGroup'])
    
    params = {
      "dryRun" => false,
      "reindexAll" => false,
      "limit" => 5000
    }

    @s.execute_post("http://localhost:8080/system/sparseupgrade", params)
    
    res = @s.execute_get(@s.url_for("/system/userManager/group/#{group.name}.tidy.json"))
    json = JSON.parse(res.body)
    assert_equal(true, json['properties']['sakai:pseudoGroup'])
    
  end
  
end