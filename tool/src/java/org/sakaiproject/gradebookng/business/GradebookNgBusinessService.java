package org.sakaiproject.gradebookng.business;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.JAXBException;

import lombok.Setter;
import lombok.extern.apachecommons.CommonsLog;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.CompareToBuilder;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.time.StopWatch;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.Section;
import org.sakaiproject.coursemanagement.api.exception.IdNotFoundException;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.gradebookng.business.dto.AssignmentOrder;
import org.sakaiproject.gradebookng.business.exception.GbException;
import org.sakaiproject.gradebookng.business.model.GbAssignmentGradeSortOrder;
import org.sakaiproject.gradebookng.business.model.GbGradeCell;
import org.sakaiproject.gradebookng.business.model.GbGradeInfo;
import org.sakaiproject.gradebookng.business.model.GbGradeLog;
import org.sakaiproject.gradebookng.business.model.GbGroup;
import org.sakaiproject.gradebookng.business.model.GbGroupType;
import org.sakaiproject.gradebookng.business.model.GbStudentGradeInfo;
import org.sakaiproject.gradebookng.business.model.GbUser;
import org.sakaiproject.gradebookng.business.util.Temp;
import org.sakaiproject.gradebookng.business.util.XmlList;
import org.sakaiproject.memory.api.Cache;
import org.sakaiproject.memory.api.MemoryService;
import org.sakaiproject.service.gradebook.shared.AssessmentNotFoundException;
import org.sakaiproject.service.gradebook.shared.Assignment;
import org.sakaiproject.service.gradebook.shared.CommentDefinition;
import org.sakaiproject.service.gradebook.shared.GradeDefinition;
import org.sakaiproject.service.gradebook.shared.GradebookNotFoundException;
import org.sakaiproject.service.gradebook.shared.GradebookService;
import org.sakaiproject.service.gradebook.shared.InvalidGradeException;
import org.sakaiproject.service.gradebook.shared.SortType;
import org.sakaiproject.site.api.Group;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.tool.gradebook.Gradebook;
import org.sakaiproject.tool.gradebook.GradingEvent;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;


/**
 * Business service for GradebookNG
 * 
 * This is not designed to be consumed outside of the application or supplied entityproviders. 
 * Use at your own risk.
 * 
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */

// TODO add permission checks! Remove logic from entityprovider if there is a double up
// TODO some of these methods pass in empty lists and its confusing. If we aren't doing paging, remove this.

@CommonsLog
public class GradebookNgBusinessService {

	@Setter
	private SiteService siteService;
	
	@Setter
	private UserDirectoryService userDirectoryService;
	
	@Setter
	private ToolManager toolManager;
	
	@Setter
	private GradebookService gradebookService;
	
	@Setter
	private CourseManagementService courseManagementService;
	
	@Setter
	private MemoryService memoryService;
	
	public static final String ASSIGNMENT_ORDER_PROP = "gbng_assignment_order";
	
	private Cache cache;
	private static final String NOTIFICATIONS_CACHE_NAME = "org.sakaiproject.gradebookng.cache.notifications";
	
	@SuppressWarnings("unchecked")
	public void init() {
		
		//max entries unbounded, no TTL eviction (TODO set this to 10 seconds?), TTI 10 seconds
		//TODO this should be configured in sakai.properties so we dont have redundant config code here
		cache = memoryService.getCache(NOTIFICATIONS_CACHE_NAME);
		if(cache == null) {
			cache = memoryService.createCache("org.sakaiproject.gradebookng.cache.notifications", null);
		}
	}
	
	
	
	/**
	 * Get a list of all users in the current site that can have grades
	 * 
	 * @return a list of users as uuids or null if none
	 */
	private List<String> getGradeableUsers() {
		try {
			String siteId = this.getCurrentSiteId();
			Set<String> userUuids = siteService.getSite(siteId).getUsersIsAllowed(Permissions.VIEW_OWN_GRADES.getValue());
			
			return new ArrayList<>(userUuids);
						
		} catch (IdUnusedException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	/**
	 * Given a list of uuids, get a list of Users
	 * 
	 * @param userUuids list of user uuids
	 * @return
	 */
	private List<User> getUsers(List<String> userUuids) throws GbException {
		
		try {
			List<User> users = userDirectoryService.getUsers(userUuids);
			Collections.sort(users, new LastNameComparator()); //TODO this needs to take into account the GbStudentSortType
			return users;
		} catch (RuntimeException e) {
			//an LDAP exception can sometimes be thrown here, catch and rethrow
			throw new GbException("An error occurred getting the list of users.", e);
		}
	}
	
	/**
	 * Helper to get a reference to the gradebook for the current site
	 * 
	 * @return the gradebook for the site
	 */
	private Gradebook getGradebook() {
		return getGradebook(this.getCurrentSiteId());
	}
	
	/**
	 * Helper to get a reference to the gradebook for the specified site
	 * 
	 * @param siteId the siteId
	 * @return the gradebook for the site
	 */
	private Gradebook getGradebook(String siteId) {
		try {
			Gradebook gradebook = (Gradebook)gradebookService.getGradebook(siteId);
			return gradebook;
		} catch (GradebookNotFoundException e) {
			log.error("No gradebook in site: " + siteId);
			return null;
		}
	}
	
	/**
	 * Get a list of assignments in the gradebook in the current site
	 * 
	 * @return a list of assignments or null if no gradebook
	 */
	public List<Assignment> getGradebookAssignments() {
		return getGradebookAssignments(this.getCurrentSiteId());
	}
	
	/**
	 * Get a list of assignments in the gradebook in the specified site, sorted by sort order
	 * 
	 * @param siteId the siteId
	 * @return a list of assignments or null if no gradebook
	 */
	public List<Assignment> getGradebookAssignments(String siteId) {
		Gradebook gradebook = getGradebook(siteId);
		if(gradebook != null) {
			return gradebookService.getAssignments(gradebook.getUid(), SortType.SORT_BY_SORTING);
		}
		return null;
	}
	
	
	
		
	/**
	 * Get a map of course grades for all users in the site, using a grade override preferentially over a calculated one
	 * key = student eid
	 * value = course grade
	 * 
	 * Note that his mpa is keyed on EID. Since the business service does not have a list of eids, to save an iteration, the calling service needs to do the filtering
	 * 
	 * @param userUuids
	 * @return the map of course grades for students, or an empty map
	 */
	@SuppressWarnings("unchecked")
	public Map<String,String> getSiteCourseGrades() {
		
		Map<String,String> courseGrades = new HashMap<>();
		
		Gradebook gradebook = this.getGradebook();
		if(gradebook != null) {
			
			//get course grades. THis new method for Sakai 11 does the override automatically, so GB1 data is preserved
			//note that this DOES not have the course grade points earned because that is in GradebookManagerHibernateImpl
			courseGrades = gradebookService.getImportCourseGrade(gradebook.getUid());
						
		}
		
		return courseGrades;
	}
	
	
	
	
	/**
	 * Save the grade and comment for a student's assignment. Ignores the concurrency check.
	 * 
	 * @param assignmentId	id of the gradebook assignment
	 * @param studentUuid	uuid of the user
	 * @param grade 		grade for the assignment/user
	 * @param comment		optional comment for the grade. Can be null.
	 * 
	 * @return
	 */
	public GradeSaveResponse saveGrade(final Long assignmentId, final String studentUuid, final String grade, final String comment) {
		
		Gradebook gradebook = this.getGradebook();
		if(gradebook == null) {
			return GradeSaveResponse.ERROR;
		}
		
		return this.saveGrade(assignmentId, studentUuid, null, grade, comment);
	}
	
	/**
	 * Save the grade and comment for a student's assignment and do concurrency checking
	 * 
	 * @param assignmentId	id of the gradebook assignment
	 * @param studentUuid	uuid of the user
	 * @param oldGrade 		old grade, passed in for concurrency checking/ If null, concurrency checking is skipped.
	 * @param newGrade		new grade for the assignment/user
	 * @param comment		optional comment for the grade. Can be null.
	 * 
	 * @return
	 * 
	 * TODO make the concurrency check a boolean instead of the null oldGrade
	 */
	public GradeSaveResponse saveGrade(final Long assignmentId, final String studentUuid, String oldGrade, String newGrade, final String comment) {
		
		Gradebook gradebook = this.getGradebook();
		if(gradebook == null) {
			return GradeSaveResponse.ERROR;
		}
		
		//get current grade
		String storedGrade = gradebookService.getAssignmentScoreString(gradebook.getUid(), assignmentId, studentUuid);
		
		//trim the .0 from the grades if present. UI removes it so lets standardise.
		storedGrade = StringUtils.removeEnd(storedGrade, ".0");
		oldGrade = StringUtils.removeEnd(oldGrade, ".0");
		newGrade = StringUtils.removeEnd(newGrade, ".0");
		
		//trim to null so we can better compare against no previous grade being recorded (as it will be null)
		//note that we also trim newGrade so that don't add the grade if the new grade is blank and there was no grade previously
		storedGrade = StringUtils.trimToNull(storedGrade);
		oldGrade = StringUtils.trimToNull(oldGrade);	
		newGrade = StringUtils.trimToNull(newGrade);	
		
		if(log.isDebugEnabled()) {
			log.debug("storedGrade: " + storedGrade);
			log.debug("oldGrade: " + oldGrade);
			log.debug("newGrade: " + newGrade);
		}

		//no change
		if(StringUtils.equals(storedGrade, newGrade)){
			return GradeSaveResponse.NO_CHANGE;
		}

		//concurrency check, if stored grade != old grade that was passed in, someone else has edited.
		//if oldGrade == null, ignore concurrency check
		if(oldGrade != null && !StringUtils.equals(storedGrade, oldGrade)) {	
			return GradeSaveResponse.CONCURRENT_EDIT;
		}
		
		//about to edit so push a notification
		pushEditingNotification(gradebook.getUid(), this.getCurrentUser(), studentUuid, assignmentId);
		
		//over limit check, get max points for assignment and check if the newGrade is over limit
		//we still save it but we return the warning
		Assignment assignment = this.getAssignment(assignmentId);
		Double maxPoints = assignment.getPoints();
		
		Double newGradePoints = NumberUtils.toDouble(newGrade);
		
		GradeSaveResponse rval = null;
		
		if(newGradePoints.compareTo(maxPoints) > 0) {
			log.debug("over limit. Max: " + maxPoints);
			rval = GradeSaveResponse.OVER_LIMIT;
		}
		
		//save
		try {
			//note, you must pass in the comment or it wil lbe nulled out by the GB service
			gradebookService.saveGradeAndCommentForStudent(gradebook.getUid(), assignmentId, studentUuid, newGrade, comment);
			if(rval == null) {
				//if we don't have some other warning, it was all OK
				rval = GradeSaveResponse.OK;				
			}
		} catch (InvalidGradeException | GradebookNotFoundException | AssessmentNotFoundException e) {
			log.error("An error occurred saving the grade. " + e.getClass() + ": " + e.getMessage());
			rval = GradeSaveResponse.ERROR;
		}
		return rval;
	}
	
	
	/**
	 * Build the matrix of assignments, students and grades for all students
	 * 
	 * @param assignments list of assignments
	 * @return
	 */
	public List<GbStudentGradeInfo> buildGradeMatrix(List<Assignment> assignments) throws GbException {
		return this.buildGradeMatrix(assignments, this.getGradeableUsers());
	}
	
	/**
	 * Build the matrix of assignments and grades for the given users.
	 * In general this is just one, as we use it for the student summary but could be more for paging etc
	 * 
	 * @param assignments list of assignments
	 * @param list of uuids
	 * @return
	 */
	public List<GbStudentGradeInfo> buildGradeMatrix(List<Assignment> assignments, List<String> studentUuids) throws GbException {
		return this.buildGradeMatrix(assignments, studentUuids, null);
	}
	
	/**
	 * Build the matrix of assignments, students and grades for all students, with the specified sortOrder
	 * 
	 * @param assignments list of assignments
	 * @param sortOrder the sort order
	 * @return
	 */
	public List<GbStudentGradeInfo> buildGradeMatrix(List<Assignment> assignments, GbAssignmentGradeSortOrder sortOrder) throws GbException {
		return this.buildGradeMatrix(assignments, this.getGradeableUsers(), sortOrder);
	}
	
	/**
	 * Build the matrix of assignments and grades for the given users with the specified sort order
	 * 
	 * @param assignments list of assignments
	 * @param list of uuids
	 * @Param sortOrder the type of sort we want. Wraps assignmentId and direction.
	 * @return
	 */
	public List<GbStudentGradeInfo> buildGradeMatrix(List<Assignment> assignments, List<String> studentUuids, GbAssignmentGradeSortOrder sortOrder) throws GbException {

		StopWatch stopwatch = new StopWatch();
		stopwatch.start();
		Temp.timeWithContext("buildGradeMatrix", "buildGradeMatrix start", stopwatch.getTime());
		
		Gradebook gradebook = this.getGradebook();
		if(gradebook == null) {
			return null;
		}
		Temp.timeWithContext("buildGradeMatrix", "getGradebook", stopwatch.getTime());
		
		//get uuids as list of Users.
		//this gives us our base list and will be sorted as per our desired sort method
		List<User> students = this.getUsers(studentUuids);
		
		//because this map is based on eid not uuid, we do the filtering later so we can save an iteration
		Map<String,String> courseGrades = this.getSiteCourseGrades();
		Temp.timeWithContext("buildGradeMatrix", "getSiteCourseGrades", stopwatch.getTime());
		
		//setup a map as we progressively build this up by adding grades to a student's entry
		Map<String, GbStudentGradeInfo> matrix = new LinkedHashMap<String, GbStudentGradeInfo>();
		
		//seed the map for all students so we can progresseively add grades to it
		//also add the course grade here, to save an iteration later
		for(User student: students) {
			
			//create and add the user info
			GbStudentGradeInfo sg = new GbStudentGradeInfo(student);

			//add the course grade
			sg.setCourseGrade(courseGrades.get(student.getEid()));
			
			//add to map so we can build on it later
			matrix.put(student.getId(), sg);
		}
		Temp.timeWithContext("buildGradeMatrix", "matrix seeded", stopwatch.getTime());
				
		//iterate over assignments and get the grades for each
		//note, the returned list only includes entries where there is a grade for the user
		//TODO maybe a new gb service method to do this, so we save iterating here?
		for(Assignment assignment: assignments) {
			
			try {
				List<GradeDefinition> defs = this.gradebookService.getGradesForStudentsForItem(gradebook.getUid(), assignment.getId(), studentUuids);
				Temp.timeWithContext("buildGradeMatrix", "getGradesForStudentsForItem: " + assignment.getId(), stopwatch.getTime());
		
				//iterate the definitions returned and update the record for each student with any grades
				for(GradeDefinition def: defs) {
					GbStudentGradeInfo sg = matrix.get(def.getStudentUid());
					
					if(sg == null) {
						log.warn("No matrix entry seeded for: " + def.getStudentUid() + ". This user may be been removed from the site");
					} else {
					
						sg.addGrade(assignment.getId(), new GbGradeInfo(def));
					}
				}
				Temp.timeWithContext("buildGradeMatrix", "updatedStudentGradeInfo: " + assignment.getId(), stopwatch.getTime());
			} catch (SecurityException e) {
				//tried to access info for a user that we aren't allowed to get for. Skip this user.
				//consider rethrowing this? Or should the UI not care.
				log.error("Error retrieving grades. Skipping.", e);
			}
		}
		
		//get the matrix as a list of GbStudentGradeInfo
		ArrayList<GbStudentGradeInfo> items = new ArrayList<>(matrix.values());

		//sort the matrix based on the supplied sort order (if any)
		if(sortOrder != null) {
			GradeComparator comparator = new GradeComparator();
			comparator.setAssignmentId(sortOrder.getAssignmentId());
			
			SortDirection direction = sortOrder.getDirection();
			
			//sort
			Collections.sort(items, comparator);
			
			//reverse if required
			if(direction == SortDirection.DESCENDING) {
				Collections.reverse(items);
			}
		}
		
		return items;
	}
	
	/**
	 * Get a list of sections and groups in a site
	 * @return
	 */
	public List<GbGroup> getSiteSectionsAndGroups() {
		String siteId = this.getCurrentSiteId();
		
		List<GbGroup> rval = new ArrayList<>();
		
		//get sections
		try {
			Set<Section> sections = courseManagementService.getSections(siteId);
			for(Section section: sections){
				rval.add(new GbGroup(section.getEid(), section.getTitle(), GbGroupType.SECTION));
			}
		} catch (IdNotFoundException e) {
			//not a course site or no sections, ignore
		}
		
		//get groups
		try {			
			Site site = siteService.getSite(siteId);
			Collection<Group> groups = site.getGroups();

			for(Group group: groups) {
				rval.add(new GbGroup(group.getId(), group.getTitle(), GbGroupType.GROUP));
			}
		} catch (IdUnusedException e) {
			//essentially ignore and use what we have
			log.error("Error retrieving groups", e);
		}
		
		Collections.sort(rval);
		
		//add the default ALL (this is a UI thing, it might not be appropriate here)
		//TODO also need to internationalse ths string
		rval.add(0, new GbGroup(null, "All Sections/Groups", GbGroupType.ALL));
		
		return rval;
		
	}
	
	/**
	 * Get a list of section memberships for the users in the site
	 * @return
	 */
	/*
	public List<String> getSectionMemberships() {
		
		List<Section> sections = getSiteSections();
		for(Section s: sections) {
			EnrollmentSet enrollmentSet = s.getEnrollmentSet();
			
			Set<Enrollment> enrollments = courseManagementService.getEnrollments(enrollmentSet.getEid());
			for(Enrollment e: enrollments) {
				
				//need to create a DTO for this
				
				//a user can be in multiple sections, need a list of sections per user
				
				//s.getTitle(); section title
				//e.getUserId(); user uuid
			}
		}
		
		return null;
		
	}
	*/
	
	
	/**
	 * Helper to get siteid.
	 * This will ONLY work in a portal site context, it will return null otherwise (ie via an entityprovider).
	 * @return
	 */
	public String getCurrentSiteId() {
		try {
    		return this.toolManager.getCurrentPlacement().getContext();
    	} catch (Exception e){
    		return null;
    	}
	}
	
	/**
     * Get the placement id of the gradebookNG tool in the site.
     * This will ONLY work in a portal site context, null otherwise
     * @return
     */
	private String getToolPlacementId() {
    	try {
    		return this.toolManager.getCurrentPlacement().getId();
    	} catch (Exception e){
    		return null;
    	}
    }
	
	/**
	 * Helper to get user
	 * @return
	 */
	public User getCurrentUser() {
		return this.userDirectoryService.getCurrentUser();
	}

    /**
     * Add a new assignment definition to the gradebook
     * @param assignment
     */
    public void addAssignment(Assignment assignment) {
        Gradebook gradebook = getGradebook();
        if(gradebook != null) {
            String gradebookId = gradebook.getUid();
            this.gradebookService.addAssignment(gradebookId, assignment);
            
            //TODO wrap this so we can catch any runtime exceptions
        }
    }
    
    /**
     * Update the order of an assignment for the current site.
	 *
     * @param assignmentId
     * @param order
     */
    public void updateAssignmentOrder(long assignmentId, int order) {
    	
    	String siteId = this.getCurrentSiteId();
		this.updateAssignmentOrder(siteId, assignmentId, order);
    }
    
    /**
     * Update the order of an assignment. If calling outside of GBNG, use this method as you can provide the site id.
     * 
     * @param siteId	the siteId
     * @param assignmentId the assignment we are reordering
     * @param order the new order
     * @throws IdUnusedException
     * @throws PermissionException
     */
    public void updateAssignmentOrder(String siteId, long assignmentId, int order) {
    	
		Gradebook gradebook = this.getGradebook(siteId);
		this.gradebookService.updateAssignmentOrder(gradebook.getUid(), assignmentId, order);
    }

	/**
	 * Update the categorized order of an assignment.
	 *
	 * @param assignmentId the assignment we are reordering
	 * @param order the new order
	 * @throws JAXBException
	 * @throws IdUnusedException
	 * @throws PermissionException
	 */
	public void updateCategorizedAssignmentOrder(long assignmentId, int order) throws JAXBException, IdUnusedException, PermissionException {
		String siteId = this.getCurrentSiteId();
		updateCategorizedAssignmentOrder(siteId, assignmentId, order);
	}


  /**
   * Update the categorized order of an assignment.
   *
   * @param siteId the site's id
   * @param assignmentId the assignment we are reordering
   * @param order the new order
   * @throws JAXBException
   * @throws IdUnusedException
   * @throws PermissionException
   */
  public void updateCategorizedAssignmentOrder(String siteId, long assignmentId, int order) throws JAXBException, IdUnusedException, PermissionException {
    Site site = null;
    try {
      site = this.siteService.getSite(siteId);
    } catch (IdUnusedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
      return;
    }

    Gradebook gradebook = (Gradebook)gradebookService.getGradebook(siteId);

    if (gradebook == null) {
      log.error(String.format("Gradebook not in site %s", siteId));
      return;
    }

    Assignment assignmentToMove = gradebookService.getAssignment(gradebook.getUid(), assignmentId);

    if (assignmentToMove == null) {
      // TODO Handle assignment not in gradebook
      log.error(String.format("Assignment %d not in site %s", assignmentId, siteId));
      return;
    }

    String category = assignmentToMove.getCategoryName();

    Map<String, List<Long>> orderedAssignments = getCategorizedAssignmentsOrder(siteId);

    if (!orderedAssignments.containsKey(category)) {
      orderedAssignments.put(category, new ArrayList<Long>());
    } else {
      orderedAssignments.get(category).remove(assignmentToMove.getId());
    }

    orderedAssignments.get(category).add(order, assignmentToMove.getId());

    storeCategorizedAssignmentsOrder(siteId, orderedAssignments);
  }


  /**
   * Get the ordered categorized assignment ids for the current site
   */
  public Map<String, List<Long>> getCategorizedAssignmentsOrder() {
    try {
      return getCategorizedAssignmentsOrder(getCurrentSiteId());
    } catch (JAXBException e) {
      e.printStackTrace();
    } catch(IdUnusedException e) {
      e.printStackTrace();
    } catch(PermissionException e) {
      e.printStackTrace();
    }
    return null;
  }


  /**
   * Get the ordered categorized assignment ids for the siteId
   *
   * @param siteId	the siteId
   * @throws JAXBException
   * @throws IdUnusedException
   * @throws PermissionException
   */
  private Map<String, List<Long>> getCategorizedAssignmentsOrder(String siteId) throws JAXBException, IdUnusedException, PermissionException {
    Site site = null;
    try {
      site = this.siteService.getSite(siteId);
    } catch (IdUnusedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
      return null;
    }

    Gradebook gradebook = (Gradebook)gradebookService.getGradebook(siteId);

    if (gradebook == null) {
      log.error(String.format("Gradebook not in site %s", siteId));
      return null;
    }

    ResourceProperties props = site.getProperties();
    String xml = props.getProperty(ASSIGNMENT_ORDER_PROP);

    if(StringUtils.isNotBlank(xml)) {
      try {
        //goes via the xml list wrapper as that is serialisable
        XmlList<AssignmentOrder> xmlList = (XmlList<AssignmentOrder>) XmlMarshaller.unmarshall(xml);
        Map<String, List<Long>> result = new HashMap<String, List<Long>>();
        List<AssignmentOrder> assignmentOrders = xmlList.getItems();

        // Sort the assignments by their category and then order
        Collections.sort(assignmentOrders, new AssignmentOrderComparator());

        for (AssignmentOrder ao : assignmentOrders) {
          // add the category if the XML doesn't have it already
          if (!result.containsKey(ao.getCategory())) {
            result.put(ao.getCategory(), new ArrayList<Long>());
          }

          result.get(ao.getCategory()).add(ao.getAssignmentId());
        }

        return result;
      } catch (JAXBException e) {
        e.printStackTrace();
      }
    } else {
      return initializeCategorizedAssignmentOrder(siteId);
    }

    return null;
  }


	/**
	 * Get the  categorized order for an assignment
	 *
	 * @param assignmentId	the assignment id
	 * @throws JAXBException
	 * @throws IdUnusedException
	 * @throws PermissionException
	 */
	public int getCategorizedSortOrder(Long assignmentId) throws JAXBException, IdUnusedException, PermissionException {
		String siteId = this.getCurrentSiteId();
		Gradebook gradebook = getGradebook(siteId);

		if(gradebook != null) {
			Assignment assignment = gradebookService.getAssignment(gradebook.getUid(), assignmentId);

			Map<String, List<Long>> categorizedOrder = getCategorizedAssignmentsOrder(siteId);
			return categorizedOrder.get(assignment.getCategoryName()).indexOf(assignmentId);
		}

		return -1;
	}


  /**
   * Set up initial Categorized Assignment Order
   */
  private Map<String, List<Long>> initializeCategorizedAssignmentOrder(String siteId) throws JAXBException, IdUnusedException, PermissionException {
    Gradebook gradebook = getGradebook(siteId);

    List<Assignment> assignments = getGradebookAssignments();

    Map<String, List<Long>> categoriesToAssignments = new HashMap<String, List<Long>>();
    Iterator<Assignment> assignmentsIterator = assignments.iterator();
    while (assignmentsIterator.hasNext()) {
      Assignment assignment = assignmentsIterator.next();
      String category = assignment.getCategoryName();
      if (!categoriesToAssignments.containsKey(category)) {
        categoriesToAssignments.put(category, new ArrayList<Long>());
      }
      categoriesToAssignments.get(category).add(assignment.getId());
    }

    storeCategorizedAssignmentsOrder(siteId, categoriesToAssignments);

    return categoriesToAssignments;
  }
  
  /**
   * Store categorized assignment order as XML on a site property
   *
   * @param siteId the site's id
   * @param assignments a list of assignments in their new order
   * @throws JAXBException
   * @throws IdUnusedException
   * @throws PermissionException
   */
  private void storeCategorizedAssignmentsOrder(String siteId, Map<String, List<Long>> categoriesToAssignments) throws JAXBException, IdUnusedException, PermissionException {
    Site site = null;
    try {
      site = this.siteService.getSite(siteId);
    } catch (IdUnusedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
      return;
    }

    List<AssignmentOrder> assignmentOrders = new ArrayList<AssignmentOrder>();

    for (String category : categoriesToAssignments.keySet()) {
      List<Long> assignmentIds = categoriesToAssignments.get(category);
      for (int i = 0; i < assignmentIds.size(); i++) {
        assignmentOrders.add(new AssignmentOrder(assignmentIds.get(i), category, i));
      }
    }

    XmlList<AssignmentOrder> newXmlList = new XmlList<AssignmentOrder>(assignmentOrders);
    String newXml = XmlMarshaller.marshal(newXmlList);

    ResourcePropertiesEdit props = site.getPropertiesEdit();
    props.addProperty(ASSIGNMENT_ORDER_PROP, newXml);

    log.debug("Updated assignment order: " + newXml);
    this.siteService.save(site);
  }


  /**
    * Comparator class for sorting a list of users by last name
    */
    class LastNameComparator implements Comparator<User> {
	    @Override
	    public int compare(User u1, User u2) {
	    	return u1.getLastName().compareTo(u2.getLastName());
	    }
    }
    
    /**
     * Comparator class for sorting a list of users by first name
     */
     class FirstNameComparator implements Comparator<User> {
 	    @Override
 	    public int compare(User u1, User u2) {
 	    	return u1.getFirstName().compareTo(u2.getFirstName());
 	    }
     }
    
     /**
      * Push a an notification into the cache that someone is editing this gradebook.
      * We store one entry in the cache per gradebook. This allows fast lookup for a given gradebookUid.
      * Within the cached object we store a map keyed on the user (eid) that performed the edit (ie could be several instructors editing at once)
      * The value of the map is a map wih a special key of assignmentid+studentUuid, again for fast lookup. We can then access the data object directly and update it. It holds the coords of a grade cell that has been edited.
      * So for a given user editing many cells there will be many GbGradeCells associated with that user.
      * These have a time associated with each so we can discard manually if desired, on lookup.
      * 
      * @param gradebookUid
      */
     private void pushEditingNotification(final String gradebookUid, final User currentUser, final String studentUuid, final long assignmentId) {
    	 
    	 //TODO Tie into the event system so other edits also participate in this
    	 
    	 //get the notifications for this gradebook
    	 Map<String,Map<String,GbGradeCell>> notifications = (Map<String,Map<String,GbGradeCell>>) cache.get(gradebookUid);
    	 
    	 Map<String,GbGradeCell> cells = null;
    	 
    	 //get or create cell map
    	 if(notifications != null) {
    		 cells = notifications.get(currentUser.getId());
    	 } else {
    		 notifications = new HashMap<>();
    	 }
    	 
    	 if(cells == null) {
    		 cells = new LinkedHashMap<>();
    	 }
    	 
    	 //push the edited cell into the map. It will add/update as required
		 cells.put(buildCellKey(studentUuid, assignmentId), new GbGradeCell(studentUuid, assignmentId));
    	 
    	 //push the new/updated cell map into the main map
    	 notifications.put(currentUser.getEid(), cells);
    	 
    	 //update the map in the cache
    	 cache.put(gradebookUid, notifications);
    	 
     }
     
     /**
      * Get a list of editing notifications for this gradebook. Excludes any notifications for the current user
      * 
      * @param gradebookUid the gradebook that we are interested in
      * @return
      */
     public List<GbGradeCell> getEditingNotifications(String gradebookUid) {
		
    	 String currentUserId = this.getCurrentUser().getEid();
    	     	 
    	 //get the notifications for this gradebook
    	 Map<String,Map<String,GbGradeCell>> notifications = (Map<String,Map<String,GbGradeCell>>) cache.get(gradebookUid);
    	 
    	 List<GbGradeCell> rval = new ArrayList<>();
    	 
    	 if(notifications != null) {
    		notifications.remove(currentUserId);
    		
    		//join the rest of the maps to get a flat list of GbGradeCells
    		for(Map<String, GbGradeCell> cells : notifications.values()) {
    			rval.addAll(cells.values());
    		}
    		
    	 }
    	 
    	 //TODO accept a timestamp and filter the list. We are only itnerested in notifications after the given timestamp
    	 //this solves the problem where old editing notifications are returned even though the user has recently refreshed the list
    	 
    	 return rval;
     }

     

     /**
      * Get an Assignment in the current site given the assignment id
      * 
      * @param siteId
      * @param assignmentId
      * @return
      */
     public Assignment getAssignment(long assignmentId) {
    	 String siteId = this.getCurrentSiteId();
    	 return this.getAssignment(siteId, assignmentId);
     }
     
     /**
      * Get an Assignment in the specified site given the assignment id
      * 
      * @param siteId
      * @param assignmentId
      * @return
      */
     public Assignment getAssignment(String siteId, long assignmentId) {
    	 Gradebook gradebook = getGradebook(siteId);
    	 if(gradebook != null) {
    		 return gradebookService.getAssignment(gradebook.getUid(), assignmentId);
    	 }
    	 return null;
     }
     
     /**
      * Get the sort order of an assignment. If the assignment has a sort order, use that.
      * Otherwise we determine the order of the assignment in the list of assignments
      * 
      * This means that we can always determine the most current sort order for an assignment, even if the list has never been sorted.
      * 
      * 
      * @param assignmentId
      * @return sort order if set, or calculated, or -1 if cannot determine at all.
      */
     public int getAssignmentSortOrder(long assignmentId) {
    	 String siteId = this.getCurrentSiteId();
    	 Gradebook gradebook = getGradebook(siteId);
    	     	 
    	 if(gradebook != null) {
    		 Assignment assignment = gradebookService.getAssignment(gradebook.getUid(), assignmentId);
    		 
    		 //if the assignment has a sort order, return that
    		 if(assignment.getSortOrder() != null) {
    			 return assignment.getSortOrder();
    		 }
    		 
    		 //otherwise we need to determine the assignment sort order within the list of assignments
    		 List<Assignment> assignments = this.getGradebookAssignments(siteId);
    		
    		 
    		 for(int i=0; i<assignments.size(); i++) {
    			 Assignment a = assignments.get(i);
    			 if(assignmentId == a.getId()) {
    				 return a.getSortOrder();
    			 }
    		 }
    	 }
    	 
    	 return -1;
     }
     
     /**
      * Update the details of an assignment
      * 
      * @param assignment
      * @return
      */
     public boolean updateAssignment(Assignment assignment) {
    	 String siteId = this.getCurrentSiteId();
    	 Gradebook gradebook = getGradebook(siteId);
    	 
    	 //need the original name as the service needs that as the key...
    	 Assignment original = this.getAssignment(assignment.getId());
    	 
    	 try {
    		 gradebookService.updateAssignment(gradebook.getUid(), original.getId(), assignment);
    		 return true;
    	 } catch (Exception e) {
    		 log.error("An error occurred updating the assignment", e);
    	 }
    	 
		 return false;
     }
     
     /**
      * Updates ungraded items in the given assignment with the given grade
      * 
      * @param assignmentId
      * @param grade
      * @return
      */
     public boolean updateUngradedItems(long assignmentId, double grade) {
    	 String siteId = this.getCurrentSiteId();
    	 Gradebook gradebook = getGradebook(siteId);
    	 
    	 //get students
    	 List<String> studentUuids = this.getGradeableUsers();
    	 
    	 //get grades (only returns those where there is a grade)
    	 List<GradeDefinition> defs = this.gradebookService.getGradesForStudentsForItem(gradebook.getUid(), assignmentId, studentUuids);

    	 //iterate and trim the studentUuids list down to those that don't have grades
    	 for(GradeDefinition def: defs) {
    		 
    		 //don't remove those where the grades are blank, they need to be updated too
    		 if(StringUtils.isNotBlank(def.getGrade())) {
    			 studentUuids.remove(def.getStudentUid());
    		 }
    	 }
    	 
    	 if(studentUuids.isEmpty()) {
    		 log.debug("Setting default grade. No students are ungraded.");
    	 }
    	
    	 try {
	    	 //for each student remaining, add the grade
	    	 for(String studentUuid : studentUuids) {
	    		 
	    		 log.debug("Setting default grade. Values of assignmentId: " + assignmentId + ", studentUuid: " + studentUuid + ", grade: " + grade);
	    		 
	    		 //TODO if this is slow doing it one by one, might be able to batch it
	    		 gradebookService.saveGradeAndCommentForStudent(gradebook.getUid(), assignmentId, studentUuid, String.valueOf(grade), null);
	    	 }
	    	 return true;
    	 } catch (Exception e) {
    		 log.error("An error occurred updating the assignment", e);
    	 }
    	 
		 return false;
     }
     
     /**
      * Get the grade log for the given student and assignment
      * 
      * @param studentUuid
      * @param assignmentId
      * @return
      */
     public List<GbGradeLog> getGradeLog(final String studentUuid, final long assignmentId) {
    	 List<GradingEvent> gradingEvents = this.gradebookService.getGradingEvents(studentUuid, assignmentId);
    	 
    	 List<GbGradeLog> rval = new ArrayList<>();
    	 for(GradingEvent ge: gradingEvents) {
    		 rval.add(new GbGradeLog(ge));
    	 }
    	 
    	 Collections.reverse(rval);
    	 
    	 return rval;
     }
     
     /**
      * Get the user given a uuid
      * @param userUuid
      * @return GbUser or null if cannot be found
      */
     public GbUser getUser(String userUuid) {
    	 try {
    		 User u = userDirectoryService.getUser(userUuid);
    		 return new GbUser(u);
    	 } catch (UserNotDefinedException e) {
    		return null; 
    	 }
     }
     
     /**
      * Get the comment for a given student assignment grade
      * 
      * @param assignmentId id of assignment
      * @param studentUuid uuid of student
      * @return the comment or null if none
      */
     public String getAssignmentGradeComment(final long assignmentId, final String studentUuid){
    	 
    	 String siteId = this.getCurrentSiteId();
    	 Gradebook gradebook = getGradebook(siteId);
    	 
    	 try {
        	 CommentDefinition def = this.gradebookService.getAssignmentScoreComment(gradebook.getUid(), assignmentId, studentUuid);
    		 if(def != null){
    			 return def.getCommentText();
    		 }
    	 } catch (GradebookNotFoundException | AssessmentNotFoundException e) {
 			log.error("An error occurred retrieving the comment. " + e.getClass() + ": " + e.getMessage());
    	 }
    	 return null;
     }
     
     /**
      * Update (or set) the comment for a student's assignment
      * 
      * @param assignmentId id of assignment
      * @param studentUuid uuid of student
      * @param comment the comment
      * @return true/false
      */
     public boolean updateAssignmentGradeComment(final long assignmentId, final String studentUuid, final String comment) {
    	 
    	 String siteId = this.getCurrentSiteId();
    	 Gradebook gradebook = getGradebook(siteId);
    	 
    	 try {
    		 //could do a check here to ensure we aren't overwriting someone else's comment that has been updated in the interim...
    		 this.gradebookService.setAssignmentScoreComment(gradebook.getUid(), assignmentId, studentUuid, comment);
    		 return true;
    	 } catch (GradebookNotFoundException | AssessmentNotFoundException | IllegalArgumentException e) {
 			log.error("An error occurred saving the comment. " + e.getClass() + ": " + e.getMessage());
    	 }
    	 
    	 return false;
     }
    

    /**
     * Comparator class for sorting a list of AssignmentOrders
     */
    class AssignmentOrderComparator implements Comparator<AssignmentOrder> {
      @Override
      public int compare(AssignmentOrder ao1, AssignmentOrder ao2) {
        // Deal with uncategorized assignments (nulls!)
        if (ao1.getCategory() == null && ao2.getCategory() == null) {
          return ((Integer) ao1.getOrder()).compareTo(ao2.getOrder());
        } else if (ao1.getCategory() == null) {
          return 1;
        } else if (ao2.getCategory() == null) {
          return -1;
        }
        // Deal with friendly categorized assignments
        if (ao1.getCategory().equals(ao2.getCategory())) {
          return ((Integer) ao1.getOrder()).compareTo(ao2.getOrder());
        } else {
          return ((String) ao1.getCategory()).compareTo(ao2.getCategory());
        }
      }
    }
    
    /**
     * Build the key to identify the cell. Used in the notifications cache.
     * @param studentUuid
     * @param assignmentId
     * @return
     */
    private String buildCellKey(String studentUuid, long assignmentId) {
    	return studentUuid + "-" + assignmentId;
    }
    
    /**
     * Comparator class for sorting an assignment by the grades
     * Note that this must have the assignmentId set into it so we can extract the appropriate grade entry from the map that each student has
     * 
     */
    class GradeComparator implements Comparator<GbStudentGradeInfo> {
    
    	@Setter
    	private long assignmentId;
    	 
		@Override
		public int compare(GbStudentGradeInfo g1, GbStudentGradeInfo g2) {
						
			GbGradeInfo info1 = g1.getGrades().get(assignmentId);
			GbGradeInfo info2 = g2.getGrades().get(assignmentId);
			
			//for proper number ordering, these have to be numerical
			Double grade1 = (info1 != null) ? NumberUtils.toDouble(info1.getGrade()) : null; 
			Double grade2 = (info2 != null) ? NumberUtils.toDouble(info2.getGrade()) : null; 
			
			return new CompareToBuilder()
			.append(grade1, grade2)
			.toComparison();
			
		}
    }
}
